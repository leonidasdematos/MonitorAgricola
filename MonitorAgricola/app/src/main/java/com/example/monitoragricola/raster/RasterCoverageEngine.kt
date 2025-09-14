// =============================================
// file: com/example/monitoragricola/raster/RasterCoverageEngine.kt (INSTRUMENTED)
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import com.example.monitoragricola.map.ProjectionHelper
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlin.math.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Engine HOT ∪ VIZ (memória efetiva = tiles próximos do trator + tiles visíveis no viewport).
 *
 * ESTA VERSÃO ESTÁ INSTRUMENTADA PARA DIAGNÓSTICO DE MEMÓRIA.
 * - Métricas detalhadas por camada (count/sections/rate/speed/frontStamp)
 * - Contadores de alocações/evicts de TileData e Bitmaps
 * - Amostragem de heap Java + heap nativo + estimativa por tiles/bitmaps
 * - Backpressure da fila e watchdog com mais contexto
 * - Logs com níveis e limiares configuráveis
 */

// ===== Parâmetros de logging/telemetria =====
const val TAG_MEM = "RASTER/MEM"
const val TAG_Q = "RASTER/Q"
const val TAG_FLUSH = "RASTER/FLUSH"
const val TAG_EVT = "RASTER/EVT"

const val MEM_LOG_INTERVAL_MS = 1500L
const val WATCHDOG_INTERVAL_MS = 2000L
const val PENDING_STUCK_MS = 10_000L

// Limiares de aviso (ajuste conforme o device)
const val WARN_HEAP_MB = 300.0   // heap Java
const val WARN_NATIVE_MB = 200.0 // heap nativo (Bitmaps, etc.)
const val WARN_TILES_MB = 180.0
const val WARN_BMPS_MB = 120.0

// ===== Config da fila =====
const val FLUSH_QUEUE_SOFT_LIMIT = 128
const val FLUSH_BATCH_MAX = 512
const val FLUSH_WORKERS = 2

class RasterCoverageEngine {
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

    // ===== Métricas/telemetria =====
    private var totalOncePx: Long = 0
    private var totalOverlapPx: Long = 0
    private val sectionPx = LongArray(32)
    private val rateSumBySection = DoubleArray(32)
    private val rateCountBySection = LongArray(32)
    private var rateSumByArea = 0.0
    private var rateCountByArea = 0L
    private var currentSpeedKmh: Float? = null

    // Stroke state
    private var strokeId: Int = 0
    private var hasLastDir = false
    private var lastUx = 1.0
    private var lastUy = 0.0

    // Parâmetros herdados
    private val STEP_TARGET_M_FACTOR = 1.00
    private val THETA_MAX_DEG = 3.0
    private val FRONT_GAP_MIN_PX = 2.0
    private val FRONT_GAP_W_FACTOR = 0.10
    private val FRONT_LEN_W_FACTOR = 0.80
    private val FRONT_LEN_MIN_PX = 0.30
    private val TAIL_LEN_W_FACTOR = 0.25
    private val TAIL_LEN_MIN_PX = 0.40

    // ====== Fila de flush ======
    private val pendingFlush = ConcurrentHashMap.newKeySet<Long>()
    private val pendingSinceNs = ConcurrentHashMap<Long, Long>()
    private val flushQueue = ConcurrentLinkedQueue<Pair<TileKey, TileData>>()
    private var flushJob: Job? = null
    private var watchdogJob: Job? = null

    // ====== Contadores (instrumentação) ======
    private val cntEnqueued = AtomicLong(0)
    private val cntSaved = AtomicLong(0)
    private val cntRequeued = AtomicLong(0)
    private val cntBatches = AtomicLong(0)
    private val cntBatchErrors = AtomicLong(0)

    private val cntTileAlloc = AtomicLong(0)
    private val cntTileFree = AtomicLong(0)
    private val cntBmpAlloc = AtomicLong(0)
    private val cntBmpFree = AtomicLong(0)
    private val cntBmpReuse = AtomicLong(0)

    private val renderBuf: IntBuffer =
        ByteBuffer.allocateDirect(tileSize * tileSize * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()


    private var lastMemLogNs = 0L
    private var lastSavedLogged = 0L
    private var lastEnqLogged = 0L

    data class DebugStats(
        val tileSize: Int,
        val resolutionM: Double,
        val hotRadius: Int,
        val tilesDataCount: Int,
        val hotCount: Int,
        val vizCount: Int,
        val bmpLruSize: Int,
        val dataLruSize: Int,
        val tileAlloc: Long,
        val tileFree: Long,
        val bmpAlloc: Long,
        val bmpFree: Long,
        val bmpReuse: Long,
    )

    fun debugStats(): DebugStats = DebugStats(
        tileSize = tileSize,
        resolutionM = resolutionM,
        hotRadius = tileRadiusHot,
        tilesDataCount = tiles.size,
        hotCount = hotSet.size,
        vizCount = vizSet.size,
        bmpLruSize = bmpLru.size(),
        dataLruSize = dataLru.size(),
        tileAlloc = cntTileAlloc.get(),
        tileFree = cntTileFree.get(),
        bmpAlloc = cntBmpAlloc.get(),
        bmpFree = cntBmpFree.get(),
        bmpReuse = cntBmpReuse.get(),
    )

    // ===== API pública (compat) =====
    fun startJob(originLat: Double, originLon: Double, resolutionM: Double = 0.10, tileSize: Int = 256) {
        this.originLat = originLat
        this.originLon = originLon
        this.projection = ProjectionHelper(originLat, originLon)
        this.resolutionM = resolutionM.coerceAtLeast(0.01)
        this.tileSize = tileSize.coerceIn(128, 1024)
        clearCoverage()
        running = true
        Log.i(TAG_EVT, "startJob origin=($originLat,$originLon) res=$resolutionM tile=$tileSize")
        logMem()
    }

    fun stopJob() { running = false; Log.i(TAG_EVT, "stopJob") }

    fun attachStore(store: TileStore?) {
        this.store = store
        // Reinicia o loop de flush para refletir o novo store (ou ausência dele)
        flushJob?.cancel()
        flushJob = null
        startFlushLoopIfNeeded()
    }
    fun currentOriginLat() = originLat
    fun currentOriginLon() = originLon
    fun currentResolutionM() = resolutionM
    fun currentTileSize() = tileSize
    fun currentProjection(): ProjectionHelper? = projection

    fun tilesSnapshot(): Set<Map.Entry<Long, TileData>> = tiles.entries.toSet()

    fun setMode(mode: HotVizMode) { renderMode = mode; invalidateTiles(); Log.i(TAG_EVT, "setMode=$mode") }

    // >>> Corrigido: medir antes/depois, sem depender do retorno de invalidateAll()
    fun invalidateTiles() {
        val before = bmpLru.size()
        bmpLru.invalidateAll()
        val freed = (before - bmpLru.size()).coerceAtLeast(0)
        cntBmpFree.addAndGet(freed.toLong())
    }

    fun clearCoverage() {
        val removed = tiles.size
        tiles.clear(); hotSet.clear(); vizSet.clear(); dataLru.clear()

        // >>> Corrigido: medir antes/depois, sem depender do retorno
        val before = bmpLru.size()
        bmpLru.invalidateAll()
        val freed = (before - bmpLru.size()).coerceAtLeast(0)

        cntTileFree.addAndGet(removed.toLong())
        cntBmpFree.addAndGet(freed.toLong())
        totalOncePx = 0; totalOverlapPx = 0
        rateSumBySection.fill(0.0); rateCountBySection.fill(0)
        rateSumByArea = 0.0; rateCountByArea = 0
        Log.i(TAG_EVT, "clearCoverage tilesFreed=$removed bmpFreed=$freed")
        logMem()
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
    suspend fun updateTractorHotCenter(lat: Double, lon: Double) {
        val proj = projection ?: return
        val p = proj.toLocalMeters(lat, lon)
        val px = floor(p.x / resolutionM).toInt()
        val py = floor(p.y / resolutionM).toInt()
        val cTx = floor(px.toDouble() / tileSize).toInt()
        val cTy = floor(py.toDouble() / tileSize).toInt()

        val newHot = HashSet<Long>((2 * tileRadiusHot + 1).let { it * it })
        for (dy in -tileRadiusHot..tileRadiusHot) for (dx in -tileRadiusHot..tileRadiusHot) {
            val tx = cTx + dx; val ty = cTy + dy
            val key = TileKey(tx, ty).pack()
            newHot.add(key)
            val existing = tiles[key]
            if (existing == null) {
                val st = store?.loadTile(tx, ty)
                val td = if (st != null) tileFromStore(st) else TileData(tileSize)
                tiles[key] = td
                dataLru.put(key, td)
                cntTileAlloc.incrementAndGet()
            }
        }

        val oldHot = HashSet<Long>(hotSet)
        val removedFromHot = oldHot.apply { removeAll(newHot) }

        hotSet.clear(); hotSet.addAll(newHot)

        for (key in removedFromHot) scheduleFlushAndMaybeEvict(key, from = "HOT")

        logMem()
        startFlushLoopIfNeeded()
    }

    suspend fun updateViewport(bb: BoundingBox) {
        val proj = projection ?: return
        val p1 = proj.toLocalMeters(bb.latSouth, bb.lonWest)
        val p2 = proj.toLocalMeters(bb.latNorth, bb.lonEast)
        val minX = floor(min(p1.x, p2.x) / resolutionM).toInt()
        val maxX = ceil(max(p1.x, p2.x) / resolutionM).toInt()
        val minY = floor(min(p1.y, p2.y) / resolutionM).toInt()
        val maxY = ceil(max(p1.y, p2.y) / resolutionM).toInt()
        val tMinX = floor(minX.toDouble() / tileSize).toInt()
        val tMaxX = floor((maxX - 1).toDouble() / tileSize).toInt()
        val tMinY = floor(minY.toDouble() / tileSize).toInt()
        val tMaxY = floor((maxY - 1).toDouble() / tileSize).toInt()

        val newViz = HashSet<Long>((tMaxX - tMinX + 1) * (tMaxY - tMinY + 1))
        for (ty in tMinY..tMaxY) for (tx in tMinX..tMaxX) {
            val key = TileKey(tx, ty).pack()
            newViz.add(key)
            if (tiles[key] == null) {
                val st = store?.loadTile(tx, ty)
                if (st != null) {
                    val td = tileFromStore(st)
                    tiles[key] = td
                    dataLru.put(key, td)
                    cntTileAlloc.incrementAndGet()
                }
            }
        }

        val oldViz = HashSet<Long>(vizSet)
        val removedFromViz = oldViz.apply { removeAll(newViz) }

        vizSet.clear(); vizSet.addAll(newViz)

        for (key in removedFromViz) scheduleFlushAndMaybeEvict(key, from = "VIZ")

        if (removedFromViz.isNotEmpty()) {
            Log.d(TAG_Q, "VIZ move: removed=${removedFromViz.size} qNow=${flushQueue.size} pending=${pendingFlush.size}")
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

        val hadDir = hasLastDir
        val prevUx = lastUx
        val prevUy = lastUy
        lastUx = ux; lastUy = uy; hasLastDir = true

        val minAdvance = 0.20 * resolutionM
        if (dist < minAdvance || !running) { invalidateTiles(); return }

        strokeId = (strokeId + 1) and 0xFFFF

        val targetStepM = STEP_TARGET_M_FACTOR * resolutionM
        var steps = max(1, round(dist / targetStepM).toInt())
        if (hadDir) {
            val dot = (prevUx * ux + prevUy * uy).coerceIn(-1.0, 1.0)
            val dTheta = acos(dot)
            val thetaMax = Math.toRadians(THETA_MAX_DEG)
            steps = max(steps, max(1, ceil(dTheta / thetaMax).toInt()))
        }

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
            drawSweptStrip(px, py, cx, cy, implementWidthMeters, activeSectionsMask, rateValue, turnSign)
            paintTailRect(cx, cy, ux, uy, implementWidthMeters, activeSectionsMask, rateValue, turnSign)
            px = cx; py = cy
        }

        markFrontMask(p1.x, p1.y, ux, uy, implementWidthMeters, computeFrontLength(implementWidthMeters))
        invalidateTiles()
    }

    private fun drawSweptStrip(
        x0: Double, y0: Double, x1: Double, y1: Double,
        widthM: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int
    ) {
        val vx = x1 - x0; val vy = y1 - y0
        val d = hypot(vx, vy)
        if (d < 0.20 * resolutionM) return
        val inv = 1.0 / d
        val ux = vx * inv; val uy = vy * inv
        val nx = -uy; val ny = ux
        rasterizeStripRect(x0, y0, ux, uy, nx, ny, d, widthM * 0.5, sectionsMask, rateValue, turnSign, isTail = false)
    }

    private fun paintTailRect(
        cx: Double, cy: Double, ux: Double, uy: Double,
        widthM: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int
    ) {
        val tailLen = max(TAIL_LEN_W_FACTOR * widthM, TAIL_LEN_MIN_PX * resolutionM)
        val nx = -uy; val ny = ux
        rasterizeStripRect(
            cx - ux * tailLen, cy - uy * tailLen, ux, uy, nx, ny, tailLen, widthM * 0.5,
            sectionsMask, rateValue, turnSign, isTail = true
        )
    }

    private fun rasterizeStripRect(
        x0: Double, y0: Double, ux: Double, uy: Double, nx: Double, ny: Double,
        d: Double, hw: Double, sectionsMask: Int, rateValue: Float?, turnSign: Int, isTail: Boolean
    ) {
        val res = resolutionM; val invRes = 1.0 / res

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

        val epsUStart = if (isTail) 0.02 * res else 0.05 * res
        val epsUEnd   = if (isTail) 0.25 * res else 0.05 * res
        val baseEpsVInner = if (isTail) 0.02 * res else 0.15 * res
        val baseEpsVOuter = if (isTail) 0.20 * res else 0.60 * res

        for (py in py0..py1) for (px in px0..px1) {
            val cx = (px + 0.5) * res; val cy = (py + 0.5) * res
            val rx = cx - x0; val ry = cy - y0
            val u = rx * ux + ry * uy
            val v = rx * nx + ry * ny
            if (u < -epsUStart || u >= d - epsUEnd) continue
            val isOuter = when { turnSign > 0 && v < 0 -> true; turnSign < 0 && v > 0 -> true; else -> false }
            val epsNeg = if (isOuter && v < 0) baseEpsVOuter else baseEpsVInner
            val epsPos = if (isOuter && v > 0) baseEpsVOuter else baseEpsVInner
            if (v < -hw - epsNeg || v > hw + epsPos) continue

            val tx = Math.floorDiv(px, tileSize); val ty = Math.floorDiv(py, tileSize)
            val key = pack(tx, ty)
            if (!hotSet.contains(key)) continue
            val tile = tiles[key] ?: continue

            val ix = px - tx * tileSize; val iy = py - ty * tileSize
            val idx = iy * tileSize + ix
            writePixel(tile, idx, sectionsMask, rateValue)
        }
    }

    private fun markFrontMask(x: Double, y: Double, ux: Double, uy: Double, widthM: Double, lengthM: Double) {
        val nx = -uy; val ny = ux
        val hw = widthM * 0.5
        val res = resolutionM; val invRes = 1.0 / res
        val gapM = max(FRONT_GAP_MIN_PX * res, FRONT_GAP_W_FACTOR * widthM)
        val startX = x + ux * gapM; val startY = y + uy * gapM
        val endX = startX + ux * lengthM; val endY = startY + uy * lengthM

        val p00x = startX - nx * hw; val p00y = startY - ny * hw
        val p01x = startX + nx * hw; val p01y = startY + ny * hw
        val p10x = endX - nx * hw; val p10y = endY - ny * hw
        val p11x = endX + nx * hw; val p11y = endY + ny * hw

        val minX = min(min(p00x, p01x), min(p10x, p11x))
        val maxX = max(max(p00x, p01x), max(p10x, p11x))
        val minY = min(min(p00y, p01y), min(p10y, p11y))
        val maxY = max(max(p00y, p01y), max(p10y, p11y))

        val px0 = floor(minX * invRes).toInt() - 1
        val py0 = floor(minY * invRes).toInt() - 1
        val px1 = ceil(maxX * invRes).toInt() + 1
        val py1 = ceil(maxY * invRes).toInt() + 1
        if (px1 < px0 || py1 < py0) return

        val curStamp: Short = strokeId.toShort()
        val epsV = 0.05 * res

        for (py in py0..py1) for (px in px0..px1) {
            val cx = (px + 0.5) * res; val cy = (py + 0.5) * res
            val rx = cx - x; val ry = cy - y
            val u = rx * ux + ry * uy
            val v = rx * nx + ry * ny
            if (u < gapM || u >= gapM + lengthM) continue
            if (abs(v) > hw + epsV) continue
            val tx = Math.floorDiv(px, tileSize); val ty = Math.floorDiv(py, tileSize)
            val key = pack(tx, ty)
            if (!hotSet.contains(key)) continue
            val tile = tiles[key] ?: continue
            val ix = px - tx * tileSize; val iy = py - ty * tileSize
            val idx = iy * tileSize + ix
            val c = tile.count[idx].toInt() and 0xFF
            if (c != 1) continue
            val fs = ensureFrontStamp(tile)
            fs[idx] = curStamp
            tile.dirty = true; tile.rev += 1
        }
    }

    private fun ensureFrontStamp(tile: TileData): ShortArray {
        var fs = tile.frontStamp
        if (fs == null) { fs = ShortArray(tileSize * tileSize); tile.frontStamp = fs }
        return fs
    }

    private fun writePixel(tile: TileData, idx: Int, sectionsMask: Int, rateValue: Float?) {
        tile.ensureLastStrokeId()
        val ls = tile.lastStrokeId!!
        val lastStroke = ls[idx]
        if (lastStroke.toInt() == strokeId) return
        ls[idx] = strokeId.toShort()

        val prev = tile.count[idx].toInt() and 0xFF
        var now = prev

        var promoted = false
        val fs = tile.frontStamp
        if (prev > 0 && fs != null) {
            val stamp = fs[idx]
            if (stamp != 0.toShort() && stamp != strokeId.toShort()) {
                promoted = true
                fs[idx] = 0
            }
        }

        if (prev == 0) now = 1 else if (promoted) now = if (prev < 255) prev + 1 else 255

        if (now == prev) {
            if (sectionsMask != 0) { tile.ensureSections(); tile.sections!![idx] = tile.sections!![idx] or sectionsMask }
            if (rateValue != null) { tile.ensureRate(); tile.rate!![idx] = rateValue }
            currentSpeedKmh?.let { v -> tile.ensureSpeed(); tile.speed!![idx] = v }
            return
        }

        tile.count[idx] = now.toByte(); tile.dirty = true; tile.rev += 1
        if (flushQueue.size > FLUSH_QUEUE_SOFT_LIMIT) Thread.yield()
        if (prev == 0) totalOncePx++ else totalOverlapPx++
        if (sectionsMask != 0) {
            tile.ensureSections(); tile.sections!![idx] = tile.sections!![idx] or sectionsMask
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

        if (!inHot && tile.dirty) {
            val s = store
            if (s != null) {
                val qBefore = flushQueue.size
                enqueueFlushOnce(keyPacked, tile)
                val qAfter = flushQueue.size
                Log.d(
                    "RASTER/Q",
                    "SCHEDULE from=$from key=$keyPacked dirty=${tile.dirty} rev=${tile.rev} " +
                            "inHot=$inHot inViz=$inViz q:$qBefore->$qAfter pending=${pendingFlush.size}"
                )
                // Sanidade: tile vindo de VIZ e sujo implica que ELE já FOI HOT no passado
                if (from == "VIZ") {
                    // Isso confirma o cenário que te expliquei
                    Log.d(
                        "RASTER/Q",
                        "VIZ->ENQUEUE key=$keyPacked: sujo por histórico (saiu do HOT antes), " +
                                "não foi sujado enquanto estava apenas em VIZ."
                    )
                }
            } else {
                // Sem store: descarta alterações para evitar vazamento de memória
                tile.dirty = false
                pendingFlush.remove(keyPacked)
                pendingSinceNs.remove(keyPacked)
                Log.w(
                    "RASTER/Q",
                    "DROP from=$from key=$keyPacked sem store; tile descartado"
                )
            }
        }

        dataLru.put(keyPacked, tile)

        if (!inHot && !inViz) {
            tiles.remove(keyPacked)

            // >>> Corrigido: medir antes/depois para saber quantos bitmaps foram liberados
            val before = bmpLru.size()
            bmpLru.invalidate(keyPacked)
            val freedBmp = (before - bmpLru.size()).coerceAtLeast(0)

            dataLru.remove(keyPacked)
            cntTileFree.incrementAndGet()
            if (freedBmp > 0) cntBmpFree.addAndGet(freedBmp.toLong())
            Log.d(TAG_EVT, "EVICT key=$keyPacked removed tile (freedBmp=$freedBmp)")
        }
    }

    private fun startFlushLoopIfNeeded() {
        if (flushJob?.isActive == true) return
        val s = store
        if (s == null) {
            Log.w(
                "RASTER/FLUSH",
                "store is NULL – limpando fila de flush para evitar uso excessivo de memória"
            )
            flushQueue.clear()
            pendingFlush.clear()
            pendingSinceNs.clear()
            return
        }

        Log.i(TAG_FLUSH, "Iniciando workers=$FLUSH_WORKERS batchMax=$FLUSH_BATCH_MAX")
        flushJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                repeat(FLUSH_WORKERS) { wid ->
                    Log.i(TAG_FLUSH, "worker#$wid STARTED")
                    launch {
                        val batch = ArrayList<Pair<TileKey, TileData>>(FLUSH_BATCH_MAX)
                        while (isActive) {
                            batch.clear()
                            while (batch.size < FLUSH_BATCH_MAX) {
                                val e = flushQueue.poll() ?: break
                                batch.add(e)
                            }
                            if (batch.isEmpty()) { delay(30); continue }

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
                                val saveMs = withContext(Dispatchers.Default) {
                                    val tSave = System.nanoTime()
                                    s.saveDirtyTilesAndClear(batch)
                                    max(1L, (System.nanoTime() - tSave) / 1_000_000)
                                }
                                for ((k, tile) in batch) {
                                    tile.dirty = false
                                    pendingFlush.remove(k.pack())
                                    pendingSinceNs.remove(k.pack())
                                }
                                // >>> Corrigido: evite coerceAtLeast(Int) → use max(1L, ...)
                                val dtMs: Long = max(1L, (System.nanoTime() - t0) / 1_000_000)
                                val mb = bytes / (1024.0 * 1024.0)
                                cntSaved.addAndGet(tilesCount.toLong())
                                cntBatches.incrementAndGet()
                                Log.d(
                                    TAG_FLUSH,
                                    "worker#$wid SAVED tiles=$tilesCount bytes=%.2fMB dt=%dms save=%dms qNow=${flushQueue.size} pending=${pendingFlush.size}"
                                        .format(mb, dtMs, saveMs)
                                )
                            } catch (e: Throwable) {
                                for (i in 0 until batch.size) flushQueue.add(batch[i])
                                cntRequeued.addAndGet(batch.size.toLong())
                                cntBatchErrors.incrementAndGet()
                                Log.w(
                                    TAG_FLUSH,
                                    "worker#$wid SAVE_ERROR tiles=$tilesCount qNow=${flushQueue.size} pending=${pendingFlush.size} ${e.message}", e
                                )
                                delay(100)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG_FLUSH, "flush supervisor died: ${e.message}", e)
            }
        }

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
                        Log.w(TAG_Q, "PENDING_STUCK count=$stuck oldest=${oldestMs}ms qNow=${flushQueue.size} pending=${pendingFlush.size}")
                    }
                    logMem()
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
        bmpLru.get(key, rev)?.let { cntBmpReuse.incrementAndGet(); return it }

        val bmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        cntBmpAlloc.incrementAndGet()
        when (renderMode) {
            HotVizMode.COBERTURA -> fillCoverageColors(tile)
            HotVizMode.SOBREPOSICAO -> fillOverlapColors(tile)
            HotVizMode.TAXA -> fillRateColors(tile)
            HotVizMode.VELOCIDADE -> fillSpeedColors(tile)
            HotVizMode.SECOES -> fillSectionsColors(tile)
        }
        renderBuf.rewind()
        bmp.copyPixelsFromBuffer(renderBuf)
        bmpLru.put(key, rev, bmp)
        return bmp
    }

    private fun fillCoverageColors(tile: TileData) {
        val n = tileSize * tileSize
        for (i in 0 until n) {
            val c = tile.count[i].toInt() and 0xFF
            val v = when {
                c == 0 -> 0
                c == 1 -> 0x8022AA22.toInt()
                else -> 0xCCFF2222.toInt()
            }
            renderBuf.put(i, v)
        }
    }

    private fun fillOverlapColors(tile: TileData) {
        val n = tileSize * tileSize
        for (i in 0 until n) {
            val c = tile.count[i].toInt() and 0xFF
            val v = if (c <= 1) 0 else 0xCCFF2222.toInt()
            renderBuf.put(i, v)
        }
    }

    private fun fillRateColors(tile: TileData) {
        val r = tile.rate
        val n = tileSize * tileSize
        if (r == null) {
            for (i in 0 until n) {
                renderBuf.put(i, 0)
            }
            return
        }
        for (i in 0 until n) {
            val v = r[i]
            val t = (v / 100f).coerceIn(0f, 1f)
            val g = (t * 255).toInt(); val b = (255 - g)
            renderBuf.put(i, (0xFF shl 24) or (0 shl 16) or (g shl 8) or (b))
        }
    }

    private fun fillSpeedColors(tile: TileData) {
        val s = tile.speed
        val n = tileSize * tileSize
        if (s == null) {
            for (i in 0 until n) {
                renderBuf.put(i, 0)
            }
            return
        }
        for (i in 0 until n) {
            val v = s[i]
            val t = (v / 20f).coerceIn(0f, 1f)
            val r = (t * 255).toInt(); val g = (255 - r)
            renderBuf.put(i, (0xFF shl 24) or (r shl 16) or (g shl 8) or 0)
        }
    }

    private fun fillSectionsColors(tile: TileData) {
        val sec = tile.sections
        val n = tileSize * tileSize
        if (sec == null) {
            for (i in 0 until n) {
                renderBuf.put(i, 0)
            }
            return
        }
        for (i in 0 until n) {
            val m = sec[i]
            val v = when {
                m == 0 -> 0
                m and 1 != 0 -> 0xFF0066FF.toInt()
                m and 2 != 0 -> 0xFF00CC66.toInt()
                m and 4 != 0 -> 0xFFFFBB00.toInt()
                else -> 0xFF999999.toInt()
            }
            renderBuf.put(i, v)
        }
    }

    // ===== Snapshots =====
    fun exportSnapshot(): RasterSnapshot {
        val list = tiles.entries.map { (k, t) ->
            val key = TileKey.unpack(k)
            t.ensureLastStrokeId()
            SnapshotTile(
                key.tx, key.ty, t.rev, t.layerMask,
                t.count.copyOf(),
                t.lastStrokeId!!.copyOf(),
                t.sections?.copyOf(), t.rate?.copyOf(), t.speed?.copyOf(), t.frontStamp?.copyOf()
            )
        }
        return RasterSnapshot(originLat, originLon, resolutionM, tileSize, list)
    }

    fun importSnapshot(snap: RasterSnapshot) {
        originLat = snap.originLat; originLon = snap.originLon; resolutionM = snap.resolutionM; tileSize = snap.tileSize
        projection = ProjectionHelper(originLat, originLon)
        clearCoverage()
        for (st in snap.tiles) {
            val td = TileData(tileSize, st.count, st.sections, st.rate, st.speed, st.lastStrokeId, st.frontStamp, st.layerMask).also { it.rev = st.rev }
            val key = TileKey(st.tx, st.ty).pack()
            tiles[key] = td
            cntTileAlloc.incrementAndGet()
        }
        invalidateTiles(); logMem()
    }

    // ===== Utilitários =====
    private fun pack(tx: Int, ty: Int): Long = (tx.toLong() shl 32) xor (ty.toLong() and 0xffffffffL)
    private fun computeFrontLength(widthM: Double): Double = max(FRONT_LEN_W_FACTOR * widthM, FRONT_LEN_MIN_PX * resolutionM)

    private fun heapJavaMB(): Double {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return used / (1024.0 * 1024.0)
    }

    private fun heapNativeMB(): Double = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

    private fun estimateTileBytes(t: TileData): Long {
        val px = (tileSize * tileSize).toLong()
        var bytes = 0L
        bytes += px * 1 // count: ByteArray
        if (t.lastStrokeId != null) bytes += px * 2 // lastStrokeId: ShortArray
        if (t.frontStamp != null) bytes += px * 2
        if (t.sections != null) bytes += px * 4
        if (t.rate != null) bytes += px * 4
        if (t.speed != null) bytes += px * 4
        return bytes
    }

    private fun estimateBmpLruBytes(): Long {
        val px = (tileSize * tileSize).toLong()
        return bmpLru.size().toLong() * px * 4 // ARGB_8888
    }

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

    private fun memBreakdown(): MemBreakdown {
        var wf = 0; var ws = 0; var wr = 0; var wv = 0
        var sumBytes = 0L
        for (t in tiles.values) {
            if (t.frontStamp != null) wf++
            if (t.sections != null) ws++
            if (t.rate != null) wr++
            if (t.speed != null) wv++
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

    fun logMem(tag: String = TAG_MEM) {
        val now = System.nanoTime()
        val since = (now - lastMemLogNs) / 1_000_000
        if (since < MEM_LOG_INTERVAL_MS && lastMemLogNs != 0L) return
        lastMemLogNs = now

        val ds = debugStats()
        val mb = memBreakdown()
        val tilesMB = mb.approxBytesTiles / (1024.0 * 1024.0)
        val bitmapsMB = mb.approxBytesBitmaps / (1024.0 * 1024.0)
        val heapMB = heapJavaMB()
        val nativeMB = heapNativeMB()

        val enq = cntEnqueued.get(); val sav = cntSaved.get()
        val dEnq = enq - lastEnqLogged; val dSav = sav - lastSavedLogged
        lastEnqLogged = enq; lastSavedLogged = sav

        val warnFlags = buildString {
            if (heapMB > WARN_HEAP_MB) append(" HEAP!")
            if (nativeMB > WARN_NATIVE_MB) append(" NATIVE!")
            if (tilesMB > WARN_TILES_MB) append(" TILES!")
            if (bitmapsMB > WARN_BMPS_MB) append(" BMPS!")
        }

        Log.d(tag,
            "tileSize=${ds.tileSize} res=${"%.2f".format(ds.resolutionM)} " +
                    "HOTr=${ds.hotRadius} dataTiles=${ds.tilesDataCount} HOT=${ds.hotCount} VIZ=${ds.vizCount} " +
                    "bmpLRU=${ds.bmpLruSize} dataLRU=${ds.dataLruSize} " +
                    "flushQ=${mb.flushQueueSize} pending=${pendingFlush.size} | " +
                    "layers(front/sections/rate/speed)=${mb.withFront}/${mb.withSections}/${mb.withRate}/${mb.withSpeed} " +
                    "memTiles=${"%.1f".format(tilesMB)}MB memBmp=${"%.1f".format(bitmapsMB)}MB heap=${"%.1f".format(heapMB)}MB native=${"%.1f".format(nativeMB)}MB | " +
                    "Δenq=$dEnq Δsav=$dSav totals(enq=$enq sav=$sav batches=${cntBatches.get()} err=${cntBatchErrors.get()}) | " +
                    "alloc(t=${ds.tileAlloc}/${ds.tileFree}) bmp(a=${ds.bmpAlloc}/r=${ds.bmpReuse}/f=${ds.bmpFree})$warnFlags"
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
            if (q == 1 || q % 16 == 0) {
                Log.d(TAG_Q, "ENQ key=${keyPacked} rev=${tile.rev} flushQ=$q pending=$p enqueuedTotal=$enq")
            }
        }
    }
}
