// =============================================
// file: com/example/monitoragricola/raster/RasterCoverageEngine.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.example.monitoragricola.map.ProjectionHelper
import com.example.monitoragricola.raster.StoreTile
import com.example.monitoragricola.raster.TileStore
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.math.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit



/**
 * Engine HOT ∪ VIZ (memória efetiva = tiles próximos do trator + tiles visíveis no viewport).
 *
 * Compatível com a API antiga usada no seu MainActivity (startJob, getAreas, export/import, etc.).
 */
private const val MEM_LOG_INTERVAL_MS = 1500L
private const val FLUSH_QUEUE_SOFT_LIMIT = 128                  // limite "saudável" para produzir backpressure
// ===== Config da fila =====
private const val FLUSH_BATCH_MAX = 512
private const val FLUSH_WORKERS = 2
private const val WATCHDOG_INTERVAL_MS = 2000L
private const val PENDING_STUCK_MS = 10_000L     // se >10s na fila, alerta





class RasterCoverageEngine {
    private var lastMemLogNs = 0L



    // ===== Config =====
    private var resolutionM: Double = 0.10
    private var tileSize: Int = 256
    private var tileRadiusHot: Int = 3
    var expandHotOnDemand: Boolean = false

    // ===== Estado global =====
    private var originLat: Double = 0.0
    private var originLon: Double = 0.0
    private var projection: ProjectionHelper? = null
    private var running: Boolean = true

    // ===== HOT/VIZ =====
    private val tiles = ConcurrentHashMap<Long, TileData>()
    private val hotSet = ConcurrentHashMap.newKeySet<Long>()
    private val vizSet = ConcurrentHashMap.newKeySet<Long>()

    // LRU
    private val dataLru = TileDataLRU(80)
    private val bmpLru = BitmapLRU(56)

    // Storage (pluggable)
    @Volatile private var store: TileStore? = null

    // Métricas
    private var totalOncePx: Long = 0
    private var totalOverlapPx: Long = 0
    private val sectionPx = LongArray(32)
    private val rateSumBySection = DoubleArray(32)
    private val rateCountBySection = LongArray(32)
    private var rateSumByArea = 0.0
    private var rateCountByArea = 0L

    // Telemetria instantânea (velocidade)
    private var currentSpeedKmh: Float? = null

    // --- Fila de flush: coalescência e backpressure ---
    private val pendingFlush = ConcurrentHashMap.newKeySet<Long>() // keys já enfileiradas (por keyPacked)


    // Flush assíncrono
    private val flushQueue = ConcurrentLinkedQueue<Pair<TileKey, TileData>>()
    private var flushJob: Job? = null

    private var strokeId: Int = 0              // ping-pong por stroke
    private var hasLastDir = false             // direção anterior válida?
    private var lastUx = 1.0
    private var lastUy = 0.0

    // ===== Parâmetros herdados do método antigo =====
    private val STEP_TARGET_M_FACTOR = 1.00        // subdivisão de stroke: ~1 px
    private val THETA_MAX_DEG        = 3.0         // suavização de curva

    private val FRONT_GAP_MIN_PX     = 2.0         // gap mínimo em pixels
    private val FRONT_GAP_W_FACTOR   = 0.10        // ~10% da largura
    private val FRONT_LEN_W_FACTOR   = 0.80        // comprimento da máscara frontal ~80% W
    private val FRONT_LEN_MIN_PX     = 0.30        // mínimo em pixels

    private val TAIL_LEN_W_FACTOR    = 0.25        // cauda curtinha ~25% W
    private val TAIL_LEN_MIN_PX      = 0.40        // mínimo em pixels

    // Métricas da fila
    private val cntEnqueued = AtomicLong(0)
    private val cntSaved = AtomicLong(0)
    private val cntRequeued = AtomicLong(0)
    private val cntBatches = AtomicLong(0)
    private val cntBatchErrors = AtomicLong(0)

    // Idade dos pendentes
    private val pendingSinceNs = ConcurrentHashMap<Long, Long>()

    // Jobs auxiliares
    private var watchdogJob: Job? = null

    data class DebugStats(
        val tileSize: Int,
        val resolutionM: Double,
        val hotRadius: Int,
        val tilesDataCount: Int,   // quantos tiles de DADOS estão vivos no mapa
        val hotCount: Int,         // quantos tiles no conjunto HOT
        val vizCount: Int,         // quantos tiles no conjunto VIZ
        val bmpLruSize: Int,       // quantos bitmaps no LRU
        val dataLruSize: Int       // quantos entries de dados no LRU (se tiver)
    )

    fun debugStats(): DebugStats {

        return DebugStats(
            tileSize = tileSize,                 // usa teu campo interno
            resolutionM = resolutionM,
            hotRadius = tileRadiusHot,
            tilesDataCount = tiles.size,         // teu mapa de tiles de dados
            hotCount = hotSet.size,
            vizCount = vizSet.size,
            bmpLruSize = bmpLru.size(),
            dataLruSize = dataLru.size()
        )
    }


    // ===== API pública (compat) =====
    fun startJob(originLat: Double, originLon: Double, resolutionM: Double = 0.10, tileSize: Int = 256) {
        this.originLat = originLat
        this.originLon = originLon
        this.projection = ProjectionHelper(originLat, originLon)
        this.resolutionM = resolutionM.coerceAtLeast(0.01)
        this.tileSize = tileSize.coerceIn(128, 1024)
        clearCoverage()
        running = true
    }
    fun stopJob() { running = false }

    fun attachStore(store: TileStore?) { this.store = store }

    fun currentOriginLat() = originLat
    fun currentOriginLon() = originLon
    fun currentResolutionM() = resolutionM
    fun currentTileSize() = tileSize

    // === Exposições leves para integração/depuração ===
    fun currentProjection(): ProjectionHelper? = projection
    /**
     * Snapshot imutável das entradas atuais de tiles.
     * Útil para depuração/diagnóstico sem expor o mapa mutável.
     */
    fun tilesSnapshot(): Set<Map.Entry<Long, TileData>> = tiles.entries.toSet()

    fun setMode(mode: HotVizMode) { renderMode = mode; invalidateTiles() }

    fun invalidateTiles() { bmpLru.invalidateAll() }

    fun clearCoverage() {
        tiles.clear(); hotSet.clear(); vizSet.clear(); dataLru.clear(); bmpLru.invalidateAll()
        totalOncePx = 0; totalOverlapPx = 0
        rateSumBySection.fill(0.0); rateCountBySection.fill(0)
        rateSumByArea = 0.0; rateCountByArea = 0
    }

    fun updateSpeed(speedKmh: Float?) { currentSpeedKmh = speedKmh }

    fun getAreas(): Areas {
        val pxArea = (totalOncePx + totalOverlapPx)
        val m2PerPx = resolutionM * resolutionM
        val totalM2 = pxArea * m2PerPx
        val overlapM2 = totalOverlapPx * m2PerPx
        return Areas(totalM2, overlapM2, sectionPx)
    }

    fun getRateStats(): RateStats {
        val meanSec = DoubleArray(32) { i -> if (rateCountBySection[i] > 0) rateSumBySection[i] / rateCountBySection[i] else 0.0 }
        val meanArea = if (rateCountByArea > 0) rateSumByArea / rateCountByArea else 0.0
        return RateStats(rateSumBySection, rateCountBySection, meanSec, rateSumByArea, rateCountByArea, meanArea)
    }

    // ===== HOT / VIZ management =====
    fun updateTractorHotCenter(lat: Double, lon: Double) {
        val proj = projection ?: return
        val p = proj.toLocalMeters(lat, lon)
        val px = floor(p.x / resolutionM).toInt()
        val py = floor(p.y / resolutionM).toInt()
        val cTx = floor(px.toDouble() / tileSize).toInt()
        val cTy = floor(py.toDouble() / tileSize).toInt()

        val newHot = HashSet<Long>( (2*tileRadiusHot+1).let { it*it } )
        for (dy in -tileRadiusHot..tileRadiusHot) {
            for (dx in -tileRadiusHot..tileRadiusHot) {
                val tx = cTx + dx; val ty = cTy + dy
                val key = TileKey(tx, ty).pack()
                newHot.add(key)
                // carrega ou cria na RAM (somente HOT pode receber escrita)
                val existing = tiles[key]
                if (existing == null) {
                    // tenta carregar do storage; se não existir, cria vazio
                    val st = store?.loadTile(tx, ty)
                    val td = if (st != null) tileFromStore(st) else TileData(tileSize)
                    tiles[key] = td
                    dataLru.put(key, td)
                }
            }
        }

        // compute removidos A PARTIR do conjunto ANTIGO
        val oldHot = HashSet<Long>(hotSet)
        val removedFromHot = oldHot.apply { removeAll(newHot) }

        // 1) Atualiza HOT primeiro
        hotSet.clear()
        hotSet.addAll(newHot)

        // 2) Agora sim, evict dos que saíram de HOT e não estão em VIZ (com conjuntos já atualizados)
        for (key in removedFromHot) {
            scheduleFlushAndMaybeEvict(key, from = "HOT")
        }

        run {
            var enq = 0
            for (key in removedFromHot) {
                val before = flushQueue.size
                scheduleFlushAndMaybeEvict(key, from = "HOT")
                if (flushQueue.size > before) enq++
            }
            Log.d("RASTER/Q",
                "HOT move: removedFromHot=${removedFromHot.size} enqueued=$enq " +
                        "qNow=${flushQueue.size} pending=${pendingFlush.size}"
            )
            Log.d("RASTER/Q",
                "HOT move: removedFromHot=${removedFromHot.size} qNow=${flushQueue.size} pending=${pendingFlush.size}"
            )
        }

        logMem()
        startFlushLoopIfNeeded()
    }

    fun updateViewport(bb: BoundingBox) {
        val proj = projection ?: return
        val p1 = proj.toLocalMeters(bb.latSouth, bb.lonWest)
        val p2 = proj.toLocalMeters(bb.latNorth, bb.lonEast)
        val minX = floor(min(p1.x,p2.x) / resolutionM).toInt()
        val maxX = ceil (max(p1.x,p2.x) / resolutionM).toInt()
        val minY = floor(min(p1.y,p2.y) / resolutionM).toInt()
        val maxY = ceil (max(p1.y,p2.y) / resolutionM).toInt()
        val tMinX = floor(minX.toDouble() / tileSize).toInt()
        val tMaxX = floor((maxX-1).toDouble() / tileSize).toInt()
        val tMinY = floor(minY.toDouble() / tileSize).toInt()
        val tMaxY = floor((maxY-1).toDouble() / tileSize).toInt()

        val newViz = HashSet<Long>((tMaxX-tMinX+1) * (tMaxY-tMinY+1))
        for (ty in tMinY..tMaxY) for (tx in tMinX..tMaxX) {
            val key = TileKey(tx, ty).pack()
            newViz.add(key)
            if (tiles[key] == null) {
                // Só carrega do storage; NÃO cria se não estiver em HOT
                val st = store?.loadTile(tx, ty)
                if (st != null) {
                    val td = tileFromStore(st)
                    tiles[key] = td
                    dataLru.put(key, td)
                }
            }
        }
        // compute removidos a partir do conjunto ANTIGO
        val oldViz = HashSet<Long>(vizSet)
        val removedFromViz = oldViz.apply { removeAll(newViz) }

        // 1) Atualiza VIZ primeiro
        vizSet.clear()
        vizSet.addAll(newViz)

        // 2) Agora sim, evict dos que saíram de VIZ e não estão em HOT (com conjuntos já atualizados)
        for (key in removedFromViz) {
            scheduleFlushAndMaybeEvict(key, from = "VIZ")
            run {
                var enq = 0
                for (key in removedFromViz) {
                    // já chamou schedule... acima, aqui só contamos diferença
                    // (se preferir, mova o contador para dentro do loop)
                    enq++ // aproximação: cada removedFromViz tentou enfileirar
                }
                Log.d("RASTER/Q",
                    "VIZ move: removedFromViz=${removedFromViz.size} (attemptedEnqueue=$enq) " +
                            "qNow=${flushQueue.size} pending=${pendingFlush.size}"
                )
                Log.d("RASTER/Q",
                    "VIZ move: removedFromViz=${removedFromViz.size} qNow=${flushQueue.size} pending=${pendingFlush.size}"
                )
            }

        }

        startFlushLoopIfNeeded()
    }

    // ===== Pintura (alto FPS) =====
    fun paintStroke(
        last: GeoPoint?,
        current: GeoPoint,
        implementWidthMeters: Double,
        activeSectionsMask: Int,
        rateValue: Float?
    ) {
        val proj = projection ?: return
        if (last == null || implementWidthMeters <= 0.01) return

        val p0 = proj.toLocalMeters(last.latitude, last.longitude)
        val p1 = proj.toLocalMeters(current.latitude, current.longitude)
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val dist = hypot(dx, dy)
        if (dist <= 1e-9) return

        val ux = dx / dist
        val uy = dy / dist

        // captura direção anterior ANTES de atualizar
        val hadDir = hasLastDir
        val prevUx = lastUx
        val prevUy = lastUy
        lastUx = ux; lastUy = uy; hasLastDir = true

        // não pinta se avanço foi ínfimo (mas atualiza heading)
        val minAdvance = 0.20 * resolutionM
        if (dist < minAdvance || !running) {
            invalidateTiles()
            return
        }

        // novo stroke (ping-pong)
        strokeId = (strokeId + 1) and 0xFFFF

        // subdivisão por comprimento + por curvatura (suaviza curvas sem fendas)
        val targetStepM = STEP_TARGET_M_FACTOR * resolutionM
        var steps = max(1, round(dist / targetStepM).toInt())
        if (hadDir) {
            val dot = (prevUx * ux + prevUy * uy).coerceIn(-1.0, 1.0)
            val dTheta = acos(dot)
            val thetaMax = Math.toRadians(THETA_MAX_DEG)
            steps = max(steps, max(1, ceil(dTheta / thetaMax).toInt()))
        }

        // sinal do lado externo da curva (para assimetria de tolerância)
        val turnSign = if (hadDir) {
            val cross = prevUx * uy - prevUy * ux
            when { cross > 1e-6 -> 1; cross < -1e-6 -> -1; else -> 0 }
        } else 0

        var px = p0.x
        var py = p0.y
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val cx = p0.x + dx * t
            val cy = p0.y + dy * t
            drawSweptStrip(
                px, py, cx, cy,
                implementWidthMeters, activeSectionsMask, rateValue, turnSign
            )
            // cauda curtinha para “varrer” a borda externa (anti-lacuna em curva)
            paintTailRect(
                cx, cy, ux, uy,
                implementWidthMeters, activeSectionsMask, rateValue, turnSign
            )
            px = cx; py = cy
        }

        // marca FRENTE invisível deste stroke para promoção de overlap no próximo passe
        markFrontMask(
            x = p1.x, y = p1.y, ux = ux, uy = uy,
            widthM = implementWidthMeters,
            lengthM = computeFrontLength(implementWidthMeters)
        )

        invalidateTiles()
    }

    // ====== SUBSTITUA: tira (retângulo varrido) com assimetria no lado externo ======
    private fun drawSweptStrip(
        x0: Double, y0: Double, x1: Double, y1: Double,
        widthM: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int
    ) {
        val vx = x1 - x0
        val vy = y1 - y0
        val d = hypot(vx, vy)
        if (d < 0.20 * resolutionM) return

        val inv = 1.0 / d
        val ux = vx * inv
        val uy = vy * inv
        val nx = -uy
        val ny = ux
        rasterizeStripRect(
            x0, y0, ux, uy, nx, ny, d, widthM * 0.5,
            sectionsMask, rateValue, turnSign, isTail = false
        )
    }

    // ====== NOVO: cauda curta colada à frente ======
    private fun paintTailRect(
        cx: Double, cy: Double, ux: Double, uy: Double,
        widthM: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int
    ) {
        val tailLen = max(TAIL_LEN_W_FACTOR * widthM, TAIL_LEN_MIN_PX * resolutionM)
        val nx = -uy
        val ny = ux
        rasterizeStripRect(
            cx - ux * tailLen, cy - uy * tailLen, ux, uy, nx, ny, tailLen, widthM * 0.5,
            sectionsMask, rateValue, turnSign, isTail = true
        )
    }

    private fun rasterizeStripRect(
        x0: Double, y0: Double, ux: Double, uy: Double, nx: Double, ny: Double,
        d: Double, hw: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int, isTail: Boolean
    ) {
        val res = resolutionM
        val invRes = 1.0 / res

        // AABB do retângulo
        val p00x = x0 - nx * hw; val p00y = y0 - ny * hw
        val p01x = x0 + nx * hw; val p01y = y0 + ny * hw
        val p10x = x0 + ux * d - nx * hw; val p10y = y0 + uy * d - ny * hw
        val p11x = x0 + ux * d + nx * hw; val p11y = y0 + uy * d + ny * hw

        val minX = min(min(p00x, p01x), min(p10x, p11x))
        val maxX = max(max(p00x, p01x), max(p10x, p11x))
        val minY = min(min(p00y, p01y), min(p10y, p11y))
        val maxY = max(max(p00y, p01y), max(p10y, p11y))

        val px0 = floor(minX * invRes).toInt() - 1
        val py0 = floor(minY * invRes).toInt() - 1
        val px1 = ceil(maxX * invRes).toInt() + 1
        val py1 = ceil(maxY * invRes).toInt() + 1
        if (px1 < px0 || py1 < py0) return

        // tolerâncias (em metros) — mais generosas no lado EXTERNO da curva
        val epsUStart = if (isTail) 0.02 * res else 0.05 * res
        val epsUEnd   = if (isTail) 0.25 * res else 0.05 * res
        val baseEpsVInner = if (isTail) 0.02 * res else 0.15 * res
        val baseEpsVOuter = if (isTail) 0.20 * res else 0.60 * res

        for (py in py0..py1) {
            for (px in px0..px1) {
                val cx = (px + 0.5) * res
                val cy = (py + 0.5) * res
                val rx = cx - x0
                val ry = cy - y0
                val u = rx * ux + ry * uy
                val v = rx * nx + ry * ny

                // meia-aberta em u: inclui o começo, exclui o fim (evita costura dupla)
                if (u < -epsUStart || u >= d - epsUEnd) continue

                val isOuter = when {
                    turnSign > 0 && v < 0 -> true
                    turnSign < 0 && v > 0 -> true
                    else -> false
                }
                val epsNeg = if (isOuter && v < 0) baseEpsVOuter else baseEpsVInner
                val epsPos = if (isOuter && v > 0) baseEpsVOuter else baseEpsVInner
                if (v < -hw - epsNeg || v > hw + epsPos) continue

                val tx = Math.floorDiv(px, tileSize)
                val ty = Math.floorDiv(py, tileSize)
                val key = pack(tx, ty)

                // Só escreve se o tile já existe e está em HOT
                if (!hotSet.contains(key)) continue
                val tile = tiles[key] ?: continue

                val ix = px - tx * tileSize
                val iy = py - ty * tileSize
                val idx = iy * tileSize + ix

                // Usa seu writePixel(TileData, ...)
                writePixel(tile, idx, sectionsMask, rateValue)
            }
        }
    }

    // Máscara SÓ NA FRENTE, com gap e pulo de pixels (decimação) ao longo de u
    private fun markFrontMask(
        x: Double, y: Double, ux: Double, uy: Double, widthM: Double, lengthM: Double
    ) {
        val nx = -uy
        val ny = ux
        val hw = widthM * 0.5
        val res = resolutionM
        val invRes = 1.0 / res

        // Gap pequeno à frente do rastro para evitar promover na mesma passada.
        // Pode ajustar se quiser mais/menos sensível.
        val gapM = max(FRONT_GAP_MIN_PX * res, FRONT_GAP_W_FACTOR * widthM) // gap clássico do antigo

        // Retângulo à frente: [gapM, gapM+lengthM] ao longo de u e [-hw, +hw] em v
        val startX = x + ux * gapM
        val startY = y + uy * gapM
        val endX   = startX + ux * lengthM
        val endY   = startY + uy * lengthM

        // AABB do retângulo
        val p00x = startX - nx * hw; val p00y = startY - ny * hw
        val p01x = startX + nx * hw; val p01y = startY + ny * hw
        val p10x = endX   - nx * hw; val p10y = endY   - ny * hw
        val p11x = endX   + nx * hw; val p11y = endY   + ny * hw

        val minX = min(min(p00x, p01x), min(p10x, p11x))
        val maxX = max(max(p00x, p01x), max(p10x, p11x))
        val minY = min(min(p00y, p01y), min(p10y, p11y))
        val maxY = max(max(p00y, p01y), max(p10y, p11y))

        val px0 = floor(minX * invRes).toInt() - 1
        val py0 = floor(minY * invRes).toInt() - 1
        val px1 = ceil (maxX * invRes).toInt() + 1
        val py1 = ceil (maxY * invRes).toInt() + 1
        if (px1 < px0 || py1 < py0) return

        val curStamp: Short = strokeId.toShort()
        val epsV = 0.05 * res   // folguinha lateral mínima

        for (py in py0..py1) for (px in px0..px1) {
            val cx = (px + 0.5) * res
            val cy = (py + 0.5) * res
            val rx = cx - x
            val ry = cy - y
            val u = rx * ux + ry * uy
            val v = rx * nx + ry * ny

            // Gap e comprimento (somente à frente)
            if (u < gapM || u >= gapM + lengthM) continue
            // Largura total do implemento (+ folga mínima)
            if (abs(v) > hw + epsV) continue

            val tx = Math.floorDiv(px, tileSize)
            val ty = Math.floorDiv(py, tileSize)
            val key = pack(tx, ty)
            if (!hotSet.contains(key)) continue
            val tile = tiles[key] ?: continue

            val ix = px - tx * tileSize
            val iy = py - ty * tileSize
            val idx = iy * tileSize + ix

            // ❗️Marcar SOMENTE pixels VERDES (count == 1)
            val c = tile.count[idx].toInt() and 0xFF
            if (c != 1) continue

            val fs = ensureFrontStamp(tile) // garante existência
            fs[idx] = curStamp
            tile.dirty = true
            tile.rev += 1
        }
    }


    private fun ensureFrontStamp(tile: TileData): ShortArray {
        var fs = tile.frontStamp
        if (fs == null) {
            fs = ShortArray(tileSize * tileSize)
            tile.frontStamp = fs
        }
        return fs
    }



    private fun rasterizeRect(x0: Double, y0: Double, ux: Double, uy: Double, nx: Double, ny: Double, len: Double, halfW: Double, sectionsMask: Int, rateValue: Float?, isTail: Boolean) {
        val res = resolutionM; val inv = 1.0 / res
        val p00x = x0 - nx*halfW; val p00y = y0 - ny*halfW
        val p01x = x0 + nx*halfW; val p01y = y0 + ny*halfW
        val p10x = x0 + ux*len - nx*halfW; val p10y = y0 + uy*len - ny*halfW
        val p11x = x0 + ux*len + nx*halfW; val p11y = y0 + uy*len + ny*halfW

        val minX = floor(min(min(p00x,p01x), min(p10x,p11x)) * inv).toInt()-1
        val maxX = ceil (max(max(p00x,p01x), max(p10x,p11x)) * inv).toInt()+1
        val minY = floor(min(min(p00y,p01y), min(p10y,p11y)) * inv).toInt()-1
        val maxY = ceil (max(max(p00y,p01y), max(p10y,p11y)) * inv).toInt()+1

        for (py in minY..maxY) for (px in minX..maxX) {
            val cx = (px + 0.5) * res; val cy = (py + 0.5) * res
            val rx = cx - x0; val ry = cy - y0
            val u = rx*ux + ry*uy
            val v = rx*(-uy) + ry*(ux) // proj no normal original
            val epsUStart = if (isTail) 0.02*res else 0.05*res
            val epsUEnd   = if (isTail) 0.25*res else 0.05*res
            val baseV     = if (isTail) 0.20*res else 0.60*res
            if (u < -epsUStart || u >= len - epsUEnd) continue
            if (abs(v) > halfW + baseV) continue

            val tx = floor(px.toDouble()/tileSize).toInt(); val ty = floor(py.toDouble()/tileSize).toInt()
            val key = TileKey(tx,ty).pack()
            // Só escreve se o tile já existe e ∈ HOT
            if (!hotSet.contains(key)) continue
            val tile = tiles[key] ?: continue
            val ix = px - tx*tileSize; val iy = py - ty*tileSize; val idx = iy*tileSize + ix
            writePixel(tile, idx, sectionsMask, rateValue)
        }
    }

    // PROMOÇÃO SÓ COM MÁSCARA DE FRENTE (sem fallback)
    private fun writePixel(tile: TileData, idx: Int, sectionsMask: Int, rateValue: Float?) {
        // Não contar 2x no mesmo stroke (subdivisões de alto FPS)
        val lastStroke = tile.lastStrokeId[idx]
        if (lastStroke.toInt() == strokeId) return
        tile.lastStrokeId[idx] = strokeId.toShort()

        val prev = tile.count[idx].toInt() and 0xFF
        var now = prev

        // Só promove se HOUVER máscara de frente de STROKE DIFERENTE
        var promoted = false
        val fs = tile.frontStamp
        if (prev > 0 && fs != null) {
            val stamp = fs[idx]
            if (stamp != 0.toShort() && stamp != strokeId.toShort()) {
                promoted = true
                fs[idx] = 0 // consome a máscara para evitar listras
            }
        }

        // Primeiro toque ou promoção válida
        if (prev == 0) {
            now = 1
        } else if (promoted) {
            now = if (prev < 255) prev + 1 else 255
        }

        // Se não mudou a contagem, atualiza só camadas auxiliares e sai
        if (now == prev) {
            if (sectionsMask != 0) {
                tile.ensureSections()
                tile.sections!![idx] = tile.sections!![idx] or sectionsMask
            }
            if (rateValue != null) {
                tile.ensureRate(); tile.rate!![idx] = rateValue
            }
            currentSpeedKmh?.let { v -> tile.ensureSpeed(); tile.speed!![idx] = v }
            return
        }

        // Commit
        tile.count[idx] = now.toByte()
        tile.dirty = true
        tile.rev += 1

        // Backpressure simples: se a fila cresceu demais, cede CPU pro worker de I/O alcançar
        if (flushQueue.size > FLUSH_QUEUE_SOFT_LIMIT) {
            // se estiver fora de coroutine, isso já ajuda; se estiver em coroutine, pode usar yield/delay(1)
            Thread.yield()
        }


        // Métricas
        if (prev == 0) totalOncePx++ else totalOverlapPx++

        // Seções/Taxa/Velocidade (opcional)
        if (sectionsMask != 0) {
            tile.ensureSections()
            tile.sections!![idx] = tile.sections!![idx] or sectionsMask
            for (b in 0 until 32) if ((sectionsMask and (1 shl b)) != 0) sectionPx[b]++
        }
        if (rateValue != null) {
            tile.ensureRate(); tile.rate!![idx] = rateValue
            val secIdx = Integer.numberOfTrailingZeros(sectionsMask.takeIf { it != 0 } ?: 1)
            if (secIdx in 0..31) { rateSumBySection[secIdx] += rateValue; rateCountBySection[secIdx]++ }
            rateSumByArea += rateValue; rateCountByArea++
        }
        currentSpeedKmh?.let { v -> tile.ensureSpeed(); tile.speed!![idx] = v }
    }

    private fun tileFromStore(st: StoreTile): TileData = TileData(
        tileSize, st.count, st.sections, st.rate, st.speed, st.lastStrokeId, st.frontStamp, st.layerMask
    ).also { it.rev = st.rev }

    private fun scheduleFlushAndMaybeEvict(keyPacked: Long, from: String) {
        val tile = tiles[keyPacked] ?: return
        val inHot = hotSet.contains(keyPacked)
        val inViz = vizSet.contains(keyPacked)

        // Só agenda flush quando NÃO está mais em HOT (para coalescer)
        if (!inHot && tile.dirty) {
            val qBefore = flushQueue.size
            enqueueFlushOnce(keyPacked, tile)
            val qAfter = flushQueue.size
            Log.d("RASTER/Q",
                "SCHEDULE from=$from key=$keyPacked dirty=${tile.dirty} rev=${tile.rev} " +
                        "inHot=$inHot inViz=$inViz q:$qBefore->$qAfter pending=${pendingFlush.size}"
            )
            // Sanidade: tile vindo de VIZ e sujo implica que ELE jÁ FOI HOT no passado
            if (from == "VIZ") {
                // Isso confirma o cenário que te expliquei
                Log.d("RASTER/Q",
                    "VIZ->ENQUEUE key=$keyPacked: sujo por histórico (saiu do HOT antes), " +
                            "não foi sujado enquanto estava apenas em VIZ."
                )
            }
        }

        // LRU
        dataLru.put(keyPacked, tile)

        // Se não pertence a HOT ∪ VIZ, evict imediato
        if (!inHot && !inViz) {
            tiles.remove(keyPacked)
            bmpLru.invalidate(keyPacked)
            dataLru.remove(keyPacked)
            Log.d("RASTER/Q",
                "EVICT key=$keyPacked removedFromRAM pendingMarked=${pendingFlush.contains(keyPacked)}"
            )
        }
    }





    private fun startFlushLoopIfNeeded() {
        if (flushJob?.isActive == true) return
        val s = store
        if (s == null) {
            Log.w("RASTER/FLUSH", "store is NULL – não vou iniciar workers; nada será drenado.")
            return
        }

        Log.i("RASTER/FLUSH", "Iniciando workers=$FLUSH_WORKERS batchMax=$FLUSH_BATCH_MAX")
        flushJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                repeat(FLUSH_WORKERS) { wid ->
                    Log.i("RASTER/FLUSH", "worker#$wid STARTED")
                    launch {
                        val batch = ArrayList<Pair<TileKey, TileData>>(FLUSH_BATCH_MAX)
                        while (isActive) {
                            batch.clear()
                            while (batch.size < FLUSH_BATCH_MAX) {
                                val e = flushQueue.poll() ?: break
                                batch.add(e)
                            }
                            if (batch.isEmpty()) { delay(30); continue }

                            // dedup e métrica iguais ao que já te passei
                            val seen = HashSet<Long>(batch.size)
                            var w = 0
                            for (i in 0 until batch.size) {
                                val kp = batch[i].first.pack()
                                if (seen.add(kp)) batch[w++] = batch[i]
                            }
                            if (w < batch.size) for (i in batch.lastIndex downTo w) batch.removeAt(i)

                            var bytes = 0L
                            for ((_, t) in batch) bytes += estimateTileBytes(t)
                            val tilesCount = batch.size
                            val t0 = System.nanoTime()

                            try {
                                s.saveDirtyTilesAndClear(batch)
                                for ((k, tile) in batch) {
                                    tile.dirty = false
                                    pendingFlush.remove(k.pack())
                                    pendingSinceNs.remove(k.pack())
                                }
                                val dtMs = ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
                                val mb = bytes / (1024.0 * 1024.0)
                                Log.d("RASTER/FLUSH",
                                    "worker#$wid SAVED tiles=$tilesCount bytes=%.2fMB dt=%dms qNow=${flushQueue.size} pending=${pendingFlush.size}"
                                        .format(mb, dtMs)
                                )
                            } catch (e: Throwable) {
                                // re-enfileira e deixa pending marcado
                                for (i in 0 until batch.size) flushQueue.add(batch[i])
                                Log.w("RASTER/FLUSH",
                                    "worker#$wid SAVE_ERROR tiles=$tilesCount qNow=${flushQueue.size} pending=${pendingFlush.size} ${e.message}", e
                                )
                                delay(100)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("RASTER/FLUSH", "flush supervisor died: ${e.message}", e)
            }
        }

        // watchdog de stuck, como te passei antes (se ainda não tem)
        if (watchdogJob?.isActive != true) {
            watchdogJob = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    val now = System.nanoTime()
                    var stuck = 0
                    var oldestMs = 0L
                    for ((_, t) in pendingSinceNs.entries) {
                        val ageMs = (now - t) / 1_000_000
                        if (ageMs > PENDING_STUCK_MS) { stuck++; if (ageMs > oldestMs) oldestMs = ageMs }
                    }
                    if (stuck > 0) {
                        Log.w("RASTER/Q",
                            "PENDING_STUCK count=$stuck oldest=${oldestMs}ms qNow=${flushQueue.size} pending=${pendingFlush.size}"
                        )
                    }
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }





    // ===== Render (para overlay) =====
    @Volatile private var renderMode: HotVizMode = HotVizMode.COBERTURA

    fun buildOrGetBitmapFor(tx: Int, ty: Int): Bitmap? {
        val key = TileKey(tx, ty).pack()
        val tile = tiles[key] ?: return null
        val rev = tile.rev
        bmpLru.get(key, rev)?.let { return it }

        val bmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val buf = IntArray(tileSize * tileSize)
        when (renderMode) {
            HotVizMode.COBERTURA -> fillCoverageColors(tile, buf)
            HotVizMode.SOBREPOSICAO -> fillOverlapColors(tile, buf)
            HotVizMode.TAXA -> fillRateColors(tile, buf)
            HotVizMode.VELOCIDADE -> fillSpeedColors(tile, buf)
            HotVizMode.SECOES -> fillSectionsColors(tile, buf)
        }
        bmp.setPixels(buf, 0, tileSize, 0, 0, tileSize, tileSize)
        bmpLru.put(key, rev, bmp)
        return bmp
    }

    private fun fillCoverageColors(tile: TileData, out: IntArray) {
        val n = out.size
        for (i in 0 until n) {
            val c = tile.count[i].toInt() and 0xFF
            if (c == 0) { out[i] = 0 } else if (c == 1) { out[i] = 0x8022AA22.toInt() } else { out[i] = 0xCCFF2222.toInt() }
        }
    }
    private fun fillOverlapColors(tile: TileData, out: IntArray) {
        val n = out.size
        for (i in 0 until n) {
            val c = tile.count[i].toInt() and 0xFF
            out[i] = if (c <= 1) 0 else 0xCCFF2222.toInt()
        }
    }
    private fun fillRateColors(tile: TileData, out: IntArray) {
        val r = tile.rate
        if (r == null) { java.util.Arrays.fill(out, 0); return }
        // mapa simples 0..100 → azul→verde
        val n = out.size
        for (i in 0 until n) {
            val v = r[i]
            val t = ((v / 100f).coerceIn(0f, 1f))
            val g = (t * 255).toInt(); val b = (255 - g)
            out[i] = (0xFF shl 24) or (0 shl 16) or (g shl 8) or (b)
        }
    }
    private fun fillSpeedColors(tile: TileData, out: IntArray) {
        val s = tile.speed
        if (s == null) { java.util.Arrays.fill(out, 0); return }
        val n = out.size
        for (i in 0 until n) {
            val v = s[i]
            val t = ((v / 20f).coerceIn(0f, 1f))
            val r = (t * 255).toInt(); val g = (255 - r)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 0
        }
    }
    private fun fillSectionsColors(tile: TileData, out: IntArray) {
        val sec = tile.sections
        if (sec == null) { java.util.Arrays.fill(out, 0); return }
        val n = out.size
        for (i in 0 until n) {
            val m = sec[i]
            out[i] = when {
                m == 0 -> 0
                m and 1 != 0 -> 0xFF0066FF.toInt()
                m and 2 != 0 -> 0xFF00CC66.toInt()
                m and 4 != 0 -> 0xFFFFBB00.toInt()
                else -> 0xFF999999.toInt()
            }
        }
    }

    // ===== Snapshots =====
    fun exportSnapshot(): RasterSnapshot {
        val list = tiles.entries.map { (k, t) ->
            val key = TileKey.unpack(k)
            SnapshotTile(key.tx, key.ty, t.rev, t.layerMask, t.count.copyOf(), t.lastStrokeId.copyOf(), t.sections?.copyOf(), t.rate?.copyOf(), t.speed?.copyOf(), t.frontStamp?.copyOf())
        }
        return RasterSnapshot(originLat, originLon, resolutionM, tileSize, list)
    }


    // ====== Utilitários que o patch usa ======
    private fun pack(tx: Int, ty: Int): Long = (tx.toLong() shl 32) xor (ty.toLong() and 0xffffffffL)
    private fun computeFrontLength(widthM: Double): Double =
        max(FRONT_LEN_W_FACTOR * widthM, FRONT_LEN_MIN_PX * resolutionM)
    fun importSnapshot(snap: RasterSnapshot) {
        originLat = snap.originLat; originLon = snap.originLon; resolutionM = snap.resolutionM; tileSize = snap.tileSize
        projection = ProjectionHelper(originLat, originLon)
        clearCoverage()
        for (st in snap.tiles) {
            val td = TileData(tileSize, st.count, st.sections, st.rate, st.speed, st.lastStrokeId, st.frontStamp, st.layerMask).also { it.rev = st.rev }
            val key = TileKey(st.tx, st.ty).pack()
            tiles[key] = td
        }
        invalidateTiles()
    }





    private fun heapUsedMB(): Double {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return used / (1024.0 * 1024.0)
    }

    private var lastSavedLogged = 0L
    private var lastEnqLogged = 0L

    fun logMem(tag: String = "RASTER") {
        val now = System.nanoTime()
        val since = (now - lastMemLogNs) / 1_000_000
        if (since < MEM_LOG_INTERVAL_MS && lastMemLogNs != 0L) return
        lastMemLogNs = now

        val ds = debugStats()
        val mb = memBreakdown()
        val tilesMB   = mb.approxBytesTiles   / (1024.0 * 1024.0)
        val bitmapsMB = mb.approxBytesBitmaps / (1024.0 * 1024.0)
        val heapMB = heapUsedMB()

        val enq = cntEnqueued.get()
        val sav = cntSaved.get()
        val dEnq = enq - lastEnqLogged
        val dSav = sav - lastSavedLogged
        lastEnqLogged = enq
        lastSavedLogged = sav

        Log.d(tag,
            "tileSize=${ds.tileSize} res=${"%.2f".format(ds.resolutionM)} " +
                    "HOTr=${ds.hotRadius} dataTiles=${ds.tilesDataCount} HOT=${ds.hotCount} VIZ=${ds.vizCount} " +
                    "bmpLRU=${ds.bmpLruSize} dataLRU=${ds.dataLruSize} " +
                    "flushQ=${mb.flushQueueSize} pending=${pendingFlush.size} | " +
                    "layers(front/sections/rate/speed)=${mb.withFront}/${mb.withSections}/${mb.withRate}/${mb.withSpeed} " +
                    "memTiles=${"%.1f".format(tilesMB)}MB memBmp=${"%.1f".format(bitmapsMB)}MB heapUsed=${"%.1f".format(heapMB)}MB | " +
                    "Δenq=$dEnq Δsav=$dSav totals(enq=$enq sav=$sav batches=${cntBatches.get()} err=${cntBatchErrors.get()})"
        )
    }


    private fun enqueueFlushOnce(keyPacked: Long, tile: TileData) {
        if (!tile.dirty) return
        if (pendingFlush.add(keyPacked)) {
            flushQueue.add(TileKey.unpack(keyPacked) to tile)
            pendingSinceNs[keyPacked] = System.nanoTime()
            val q = flushQueue.size
            val p = pendingFlush.size
            val enq = cntEnqueued.incrementAndGet()

            // Log leve apenas quando cruza degraus (evita spam)
            if (q == 1 || q % 16 == 0) {
                Log.d("RASTER/Q",
                    "ENQ key=${keyPacked} rev=${tile.rev} reason=leftHOT|evict " +
                            "flushQ=$q pending=$p enqueuedTotal=$enq"
                )
            }
        }
    }





    // ===== Métrica de memória por camada =====
    private data class MemBreakdown(
        val tiles: Int,
        val withFront: Int,
        val withSections: Int,
        val withRate: Int,
        val withSpeed: Int,
        val approxBytesTiles: Long,
        val approxBytesBitmaps: Long,
        val flushQueueSize: Int,
    )

    // bytes por elemento
    private inline val b1 get() = 1L
    private inline val b2 get() = 2L
    private inline val b4 get() = 4L

    // Estimativa do peso do TileData conforme camadas presentes
    private fun estimateTileBytes(t: TileData): Long {
        val px = (tileSize * tileSize).toLong()
        var bytes = 0L
        // base sempre presentes
        bytes += px * b1            // count: ByteArray
        bytes += px * b2            // lastStrokeId: ShortArray

        // opcionais
        if (t.frontStamp != null) bytes += px * b2      // ShortArray
        if (t.sections   != null) bytes += px * b4      // IntArray
        if (t.rate       != null) bytes += px * b4      // FloatArray
        if (t.speed      != null) bytes += px * b4      // FloatArray
        return bytes
    }

    // Estimativa do peso dos bitmaps no LRU (ARGB_8888 = 4 B/px)
    private fun estimateBmpLruBytes(): Long {
        val px = (tileSize * tileSize).toLong()
        // se o BitmapLRU não expõe iteração, use o .size() mesmo (estimativa)
        return bmpLru.size().toLong() * px * b4
    }

    private fun memBreakdown(): MemBreakdown {
        var wf = 0; var ws = 0; var wr = 0; var wv = 0
        var sumBytes = 0L
        for (t in tiles.values) {
            if (t.frontStamp != null) wf++
            if (t.sections   != null) ws++
            if (t.rate       != null) wr++
            if (t.speed      != null) wv++
            sumBytes += estimateTileBytes(t)
        }
        val bmpBytes = estimateBmpLruBytes()
        return MemBreakdown(
            tiles = tiles.size,
            withFront = wf,
            withSections = ws,
            withRate = wr,
            withSpeed = wv,
            approxBytesTiles = sumBytes,
            approxBytesBitmaps = bmpBytes,
            flushQueueSize = flushQueue.size
        )
    }

}
