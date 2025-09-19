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
import kotlin.coroutines.coroutineContext
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

private class LongArraySet(initialCapacity: Int = 16) {
    private var keys = LongArray(nextPowerOfTwo(initialCapacity.coerceAtLeast(1)))
    private var states = ByteArray(keys.size)
    private var _size = 0
    private var threshold = computeThreshold(keys.size)

    val size: Int
        get() = _size

    fun isEmpty(): Boolean = _size == 0

    fun add(value: Long) {
        if (_size >= threshold) {
            rehash(keys.size shl 1)
        }
        insert(value)
    }

    fun clear() {
        if (_size == 0) return
        states.fill(0)
        _size = 0
    }

    inline fun forEach(action: (Long) -> Unit) {
        for (index in keys.indices) {
            if (states[index].toInt() == 1) {
                action(keys[index])
            }
        }
    }

    private fun insert(value: Long) {
        var idx = indexFor(value, keys.size)
        val mask = keys.size - 1
        while (true) {
            val state = states[idx].toInt()
            if (state == 0) {
                keys[idx] = value
                states[idx] = 1
                _size++
                return
            }
            if (state == 1 && keys[idx] == value) {
                return
            }
            idx = (idx + 1) and mask
        }
    }

    private fun rehash(newCapacity: Int) {
        val oldKeys = keys
        val oldStates = states
        keys = LongArray(newCapacity)
        states = ByteArray(newCapacity)
        _size = 0
        threshold = computeThreshold(newCapacity)
        val mask = newCapacity - 1
        for (i in oldKeys.indices) {
            if (oldStates[i].toInt() == 1) {
                var idx = indexFor(oldKeys[i], newCapacity)
                while (states[idx].toInt() == 1) {
                    idx = (idx + 1) and mask
                }
                keys[idx] = oldKeys[i]
                states[idx] = 1
                _size++
            }
        }
    }

    private fun computeThreshold(capacity: Int): Int {
        return max(1, (capacity * 0.75f).toInt())
    }

    private fun indexFor(value: Long, capacity: Int): Int {
        val mask = capacity - 1
        return mix(value) and mask
    }

    private fun mix(value: Long): Int {
        var v = value
        v = v xor (v ushr 33)
        v *= -0xae502812aa7333L
        v = v xor (v ushr 33)
        v *= -0x3b314601e57a13adL
        v = v xor (v ushr 33)
        return v.toInt()
    }

    companion object {
        private fun nextPowerOfTwo(value: Int): Int {
            var v = 1
            while (v < value) {
                v = v shl 1
            }
            return v
        }
    }
}

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
    private var tileRadiusHot: Int = 5
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
    private val pendingStoreLoads = ConcurrentHashMap.newKeySet<Long>()


    // LRU
    private val dataLru = TileDataLRU(80)
    private val bmpLru = BitmapLRU(56)

    // Storage (pluggable)
    @Volatile private var store: TileStore? = null
    @Volatile private var restoringFromStore: Boolean = false


    // ===== Métricas/telemetria =====
    private var totalOncePx: Long = 0
    private var totalOverlapPx: Long = 0
    private val sectionPx = LongArray(32)
    private val rateSumBySection = DoubleArray(32)
    private val rateCountBySection = LongArray(32)
    private var rateSumByArea = 0.0
    private var rateCountByArea = 0L
    private val totalsLock = Any()
    private val restoredTotals = ConcurrentHashMap.newKeySet<Long>()
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

    @Volatile
    private var renderBuf: IntArray = IntArray(tileSize * tileSize)


    private var lastMemLogNs = 0L
    private var lastSavedLogged = 0L
    private var lastEnqLogged = 0L

    private val dirtyTileKeys = LongArraySet(128)


    private fun resetRenderBufferForTileSize(newTileSize: Int) {
        val required = newTileSize * newTileSize
        if (renderBuf.size != required) {
            renderBuf = IntArray(required)
        }
    }

    private fun ensureRenderBufferCapacity(requiredPixels: Int) {
        val needed = max(requiredPixels, tileSize * tileSize)
        if (renderBuf.size < needed) {
            renderBuf = IntArray(needed)
        }
    }


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
        resetRenderBufferForTileSize(this.tileSize)
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

    private fun markTileDirty(key: Long) {
        dirtyTileKeys.add(key)
    }

    private fun invalidateDirtyTileBitmaps() {
        if (dirtyTileKeys.isEmpty()) return
        val before = bmpLru.size()
        dirtyTileKeys.forEach { key -> bmpLru.invalidate(key) }
        val freed = (before - bmpLru.size()).coerceAtLeast(0)
        if (freed > 0) {
            cntBmpFree.addAndGet(freed.toLong())
        }
        dirtyTileKeys.clear()
    }


    fun clearCoverage() {
        val removed = tiles.size
        tiles.clear(); hotSet.clear(); vizSet.clear(); dataLru.clear(); pendingStoreLoads.clear()

        // >>> Corrigido: medir antes/depois, sem depender do retorno
        val before = bmpLru.size()
        bmpLru.invalidateAll()
        val freed = (before - bmpLru.size()).coerceAtLeast(0)

        cntTileFree.addAndGet(removed.toLong())
        cntBmpFree.addAndGet(freed.toLong())
        totalOncePx = 0; totalOverlapPx = 0
        sectionPx.fill(0)
        rateSumBySection.fill(0.0); rateCountBySection.fill(0)
        rateSumByArea = 0.0; rateCountByArea = 0
        restoredTotals.clear()
        Log.i(TAG_EVT, "clearCoverage tilesFreed=$removed bmpFreed=$freed")
        logMem()
    }

    fun beginStoreRestore() {
        restoringFromStore = true
        clearCoverage()
    }

    fun finishStoreRestore() {
        restoringFromStore = false
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
        if (restoringFromStore) return
        coroutineContext.ensureActive()
        val proj = projection ?: return
        coroutineContext.ensureActive()
        val p = proj.toLocalMeters(lat, lon)
        val px = floor(p.x / resolutionM).toInt()
        val py = floor(p.y / resolutionM).toInt()
        val cTx = floor(px.toDouble() / tileSize).toInt()
        val cTy = floor(py.toDouble() / tileSize).toInt()

        val newHot = HashSet<Long>((2 * tileRadiusHot + 1).let { it * it })
        // Prefetch: chaves que acabaram de entrar ou estão pendentes + anel (r+1)
        val storeRef = store
        val hotPrefetch = if (storeRef != null) LinkedHashSet<TileKey>() else null
        for (dy in -tileRadiusHot..tileRadiusHot) {
            coroutineContext.ensureActive()
            for (dx in -tileRadiusHot..tileRadiusHot) {
                coroutineContext.ensureActive()
                val tx = cTx + dx; val ty = cTy + dy
                val key = TileKey(tx, ty).pack()
                newHot.add(key)
                val existing = tiles[key]
                val shouldRetry = existing == null || (storeRef != null && pendingStoreLoads.contains(key))

                // Prefetch dos que entraram no HOT e ainda não estão prontos
                if (hotPrefetch != null && shouldRetry) {
                    hotPrefetch.add(TileKey(tx, ty))
                }

                if (shouldRetry) {
                    val st = storeRef?.loadTile(tx, ty)
                    if (st != null) {
                        val wasPending = pendingStoreLoads.remove(key)
                        val td = tileFromStore(st, forceMetrics = wasPending)
                        tiles[key] = td
                        dataLru.put(key, td)
                        if (existing == null) {
                            cntTileAlloc.incrementAndGet()
                        }
                    } else {
                        if (existing == null) {
                            restoredTotals.add(key)
                            val td = TileData(tileSize)
                            tiles[key] = td
                            dataLru.put(key, td)
                            cntTileAlloc.incrementAndGet()
                        }
                        pendingStoreLoads.add(key)
                    }
                }
            }
        }

        // Prefetch do anel imediatamente fora do HOT (tileRadiusHot + 1)
        if (hotPrefetch != null) {
            val ring = tileRadiusHot + 1
            if (ring > 0) {
                for (dy in -ring..ring) {
                    coroutineContext.ensureActive()
                    for (dx in -ring..ring) {
                        coroutineContext.ensureActive()
                        if (max(abs(dx), abs(dy)) != ring) continue
                        val tx = cTx + dx; val ty = cTy + dy
                        val k = pack(tx, ty)
                        val missingOrPending = (tiles[k] == null) || pendingStoreLoads.contains(k)
                        if (missingOrPending) hotPrefetch.add(TileKey(tx, ty))
                    }
                }
            }
            coroutineContext.ensureActive()
            if (hotPrefetch.isNotEmpty()) {
                // off-main; este método já roda em Dispatchers.Default, mas garantimos
                withContext(Dispatchers.Default) { storeRef?.prefetchTiles(hotPrefetch) }
            }
        }

        val previousHot = HashSet<Long>(hotSet)
        val removedFromHot = HashSet<Long>()
        for (existing in previousHot) {
            if (!newHot.contains(existing)) {
                removedFromHot.add(existing)
            }
        }

        val addedToHot = ArrayList<Long>()
        for (key in newHot) {
            if (!previousHot.contains(key)) {
                addedToHot.add(key)
            }
        }
        if (addedToHot.isNotEmpty()) {
            hotSet.addAll(addedToHot)
        }

        if (removedFromHot.isNotEmpty()) {
            coroutineContext.ensureActive()
            for (key in removedFromHot) {
                coroutineContext.ensureActive()
                hotSet.remove(key)
            }
            coroutineContext.ensureActive()
            for (key in removedFromHot) {
                coroutineContext.ensureActive()
                scheduleFlushAndMaybeEvict(key, from = "HOT")
            }        }
        logMem()
        startFlushLoopIfNeeded()
    }

    suspend fun updateViewport(bb: BoundingBox) {
        if (restoringFromStore) return
        coroutineContext.ensureActive()
        val proj = projection ?: return
        coroutineContext.ensureActive()
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
        val storeRef = store
        // Prefetch: tudo que acabou de entrar em VIZ e ainda não está pronto
        val vizPrefetch = if (storeRef != null) LinkedHashSet<TileKey>() else null

        for (ty in tMinY..tMaxY) {
            coroutineContext.ensureActive()
            for (tx in tMinX..tMaxX) {
                coroutineContext.ensureActive()
                val key = TileKey(tx, ty).pack()
                newViz.add(key)
                val existing = tiles[key]
                val shouldRetry =
                    existing == null || (storeRef != null && pendingStoreLoads.contains(key))

                if (vizPrefetch != null && shouldRetry) {
                    vizPrefetch.add(TileKey(tx, ty))
                }

                if (!shouldRetry) continue

                val st = storeRef?.loadTile(tx, ty)
                if (st != null) {
                    val wasPending = pendingStoreLoads.remove(key)
                    val td = tileFromStore(st, forceMetrics = wasPending)
                    tiles[key] = td
                    dataLru.put(key, td)
                    if (existing == null) {
                        cntTileAlloc.incrementAndGet()
                    }
                } else {
                    if (existing == null) {
                        restoredTotals.add(key)
                        val td = TileData(tileSize)
                        tiles[key] = td
                        dataLru.put(key, td)
                        cntTileAlloc.incrementAndGet()
                    }
                    pendingStoreLoads.add(key)
                }
            }
        }
        if (vizPrefetch != null && vizPrefetch.isNotEmpty()) {
            coroutineContext.ensureActive()
            withContext(Dispatchers.Default) { storeRef?.prefetchTiles(vizPrefetch) }
        }

        val oldViz = HashSet<Long>(vizSet)
        val removedFromViz = oldViz.apply { removeAll(newViz) }

        vizSet.clear(); vizSet.addAll(newViz)

        coroutineContext.ensureActive()
        for (key in removedFromViz) {
            coroutineContext.ensureActive()
            scheduleFlushAndMaybeEvict(key, from = "VIZ")
        }
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
        dirtyTileKeys.clear()
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
        if (dist < minAdvance || !running) { invalidateDirtyTileBitmaps(); return }

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
        invalidateDirtyTileBitmaps()
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

    private fun forEachHotTileInBounds(
        px0: Int,
        py0: Int,
        px1: Int,
        py1: Int,
        action: (keyPacked: Long, tile: TileData, tileOriginX: Int, tileOriginY: Int, startPx: Int, startPy: Int, endPx: Int, endPy: Int) -> Unit
    ) {
        if (px1 < px0 || py1 < py0) return

        val tileMinX = floorDivInt(px0, tileSize)
        val tileMaxX = floorDivInt(px1, tileSize)
        val tileMinY = floorDivInt(py0, tileSize)
        val tileMaxY = floorDivInt(py1, tileSize)
        if (tileMaxX < tileMinX || tileMaxY < tileMinY) return

        for (ty in tileMinY..tileMaxY) {
            val tileOriginY = ty * tileSize
            val startPyTile = max(py0, tileOriginY)
            val endPyTile = min(py1, tileOriginY + tileSize - 1)
            if (startPyTile > endPyTile) continue

            for (tx in tileMinX..tileMaxX) {
                val tileOriginX = tx * tileSize
                val startPxTile = max(px0, tileOriginX)
                val endPxTile = min(px1, tileOriginX + tileSize - 1)
                if (startPxTile > endPxTile) continue

                val key = pack(tx, ty)
                if (!hotSet.contains(key)) continue
                val tile = tiles[key] ?: continue

                action(key, tile, tileOriginX, tileOriginY, startPxTile, startPyTile, endPxTile, endPyTile)
            }
        }
    }

    private fun floorDivInt(a: Int, b: Int): Int {
        var q = a / b
        val r = a % b
        if (r != 0 && (a xor b) < 0) q--
        return q
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
        val tileStride = tileSize

        forEachHotTileInBounds(px0, py0, px1, py1) { keyPacked, tile, tileOriginX, tileOriginY, startPx, startPy, endPx, endPy ->
            val localStartX = startPx - tileOriginX
            val localEndX = endPx - tileOriginX
            val localStartY = startPy - tileOriginY
            val localEndY = endPy - tileOriginY

            for (localPy in localStartY..localEndY) {
                val py = tileOriginY + localPy
                val cy = (py + 0.5) * res
                val ry = cy - y0
                val baseU = ry * uy
                val baseV = ry * ny
                val rowIndex = localPy * tileStride

                for (localPx in localStartX..localEndX) {
                    val px = tileOriginX + localPx
                    val cx = (px + 0.5) * res
                    val rx = cx - x0

                    val u = rx * ux + baseU
                    if (u < -epsUStart || u >= d - epsUEnd) continue

                    val v = rx * nx + baseV
                    val isOuter = when {
                        turnSign > 0 && v < 0 -> true
                        turnSign < 0 && v > 0 -> true
                        else -> false
                    }
                    val epsNeg = if (isOuter && v < 0) baseEpsVOuter else baseEpsVInner
                    val epsPos = if (isOuter && v > 0) baseEpsVOuter else baseEpsVInner
                    if (v < -hw - epsNeg || v > hw + epsPos) continue

                    val idx = rowIndex + localPx
                    if (writePixel(tile, idx, sectionsMask, rateValue)) {
                        markTileDirty(keyPacked)
                    }
                }
            }
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
        val tileStride = tileSize

        forEachHotTileInBounds(px0, py0, px1, py1) { keyPacked, tile, tileOriginX, tileOriginY, startPx, startPy, endPx, endPy ->
            val localStartX = startPx - tileOriginX
            val localEndX = endPx - tileOriginX
            val localStartY = startPy - tileOriginY
            val localEndY = endPy - tileOriginY
            var frontStamp = tile.frontStamp

            for (localPy in localStartY..localEndY) {
                val py = tileOriginY + localPy
                val cy = (py + 0.5) * res
                val ry = cy - y
                val baseU = ry * uy
                val baseV = ry * ny
                val rowIndex = localPy * tileStride

                for (localPx in localStartX..localEndX) {
                    val px = tileOriginX + localPx
                    val cx = (px + 0.5) * res
                    val rx = cx - x
                    val u = rx * ux + baseU
                    if (u < gapM || u >= gapM + lengthM) continue

                    val v = rx * nx + baseV
                    if (abs(v) > hw + epsV) continue

                    val idx = rowIndex + localPx
                    val c = tile.count[idx].toInt() and 0xFF
                    if (c != 1) continue

                    val fs = frontStamp ?: ensureFrontStamp(tile).also { frontStamp = it }
                    fs[idx] = curStamp
                    tile.dirty = true; tile.rev += 1
                    markTileDirty(keyPacked)
                }
            }
        }
    }


    private fun ensureFrontStamp(tile: TileData): ShortArray {
        var fs = tile.frontStamp
        if (fs == null) { fs = ShortArray(tileSize * tileSize); tile.frontStamp = fs }
        return fs
    }

    private fun writePixel(tile: TileData, idx: Int, sectionsMask: Int, rateValue: Float?): Boolean {
        tile.ensureLastStrokeId()
        val ls = tile.lastStrokeId!!
        val lastStroke = ls[idx]
        if (lastStroke.toInt() == strokeId) return false
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
            return false
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
        return true
    }

    private fun tileFromStore(st: StoreTile, forceMetrics: Boolean = false): TileData {
        return applyRestoredTileMetrics(st, keepTileInMemory = true, force = forceMetrics)
            ?: TileData(
                tileSize, st.count, st.sections, st.rate, st.speed, st.lastStrokeId, st.frontStamp, st.layerMask
            ).also { it.rev = st.rev }
    }

    fun applyRestoredTileMetrics(tile: StoreTile, keepTileInMemory: Boolean = false, force: Boolean = false): TileData? {
        val keyPacked = TileKey(tile.tx, tile.ty).pack()
        accumulateRestoredMetrics(keyPacked, tile.count, tile.sections, tile.rate, force)
        if (!keepTileInMemory) return null


        return TileData(
            tileSize,
            tile.count,
            tile.sections,
            tile.rate,
            tile.speed,
            tile.lastStrokeId,
            tile.frontStamp,
            tile.layerMask
        ).also { it.rev = tile.rev }
    }

    private fun accumulateRestoredMetrics(
        keyPacked: Long,
        count: ByteArray,
        sections: IntArray?,
        rate: FloatArray?,
        force: Boolean = false
    ) {
        if (!force) {
            if (!restoredTotals.add(keyPacked)) return
        } else {
            restoredTotals.add(keyPacked)
        }
        var once = 0L
        var overlap = 0L
        val sectionDelta = LongArray(sectionPx.size)
        val rateSumDelta = DoubleArray(rateSumBySection.size)
        val rateCountDelta = LongArray(rateCountBySection.size)
        var rateSumAreaDelta = 0.0
        var rateCountAreaDelta = 0L

        val sectionsArr = sections
        val rateArr = rate
        val limit = count.size

        for (i in 0 until limit) {
            val cInt = count[i].toInt() and 0xFF
            if (cInt == 0) continue

            once++
            overlap += (cInt - 1).toLong()

            sectionsArr?.let { secArr ->
                var bits = secArr[i]
                if (bits != 0) {
                    val cLong = cInt.toLong()
                    var bitIdx = 0
                    while (bits != 0 && bitIdx < sectionDelta.size) {
                        if ((bits and 1) != 0) sectionDelta[bitIdx] += cLong
                        bits = bits ushr 1
                        bitIdx++
                    }
                }
            }

            rateArr?.let { rateValues ->
                val rateValue = rateValues[i].toDouble()
                val secMask = sectionsArr?.get(i) ?: 0
                val secIdx = Integer.numberOfTrailingZeros(if (secMask != 0) secMask else 1)
                if (secIdx in 0 until rateSumDelta.size) {
                    rateSumDelta[secIdx] += rateValue
                    rateCountDelta[secIdx]++
                }
                rateSumAreaDelta += rateValue
                rateCountAreaDelta++
            }
        }

        synchronized(totalsLock) {
            totalOncePx += once
            totalOverlapPx += overlap
            for (i in sectionDelta.indices) {
                val delta = sectionDelta[i]
                if (delta != 0L) sectionPx[i] += delta
            }
            for (i in rateSumDelta.indices) {
                if (rateCountDelta[i] != 0L || rateSumDelta[i] != 0.0) {
                    rateSumBySection[i] += rateSumDelta[i]
                    rateCountBySection[i] += rateCountDelta[i]
                }
            }
            rateSumByArea += rateSumAreaDelta
            rateCountByArea += rateCountAreaDelta
        }
    }

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
            maybeEvictColdTile(keyPacked, tile, reason = "schedule from=$from")
        }
    }

    private fun maybeEvictColdTile(keyPacked: Long, tile: TileData, reason: String) {
        if (hotSet.contains(keyPacked) || vizSet.contains(keyPacked)) return
        if (pendingFlush.contains(keyPacked)) {
            Log.d(TAG_EVT, "DEFER EVICT key=$keyPacked reason=$reason pendingFlush")
            return
        }
        if (tile.dirty) {
            Log.d(TAG_EVT, "SKIP EVICT key=$keyPacked reason=$reason tile still dirty")
            return
        }

        val removed = tiles.remove(keyPacked)

        // >>> Corrigido: medir antes/depois para saber quantos bitmaps foram liberados
        val before = bmpLru.size()
        bmpLru.invalidate(keyPacked)
        val freedBmp = (before - bmpLru.size()).coerceAtLeast(0)
        dataLru.remove(keyPacked)

        if (pendingStoreLoads.remove(keyPacked)) {
            restoredTotals.remove(keyPacked)
        }


        if (removed != null) {
            cntTileFree.incrementAndGet()
            if (freedBmp > 0) cntBmpFree.addAndGet(freedBmp.toLong())
            Log.d(TAG_EVT, "EVICT key=$keyPacked removed tile (freedBmp=$freedBmp) reason=$reason")
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
                            val optBefore = optArrCount()


                            try {
                                val saveMs = withContext(Dispatchers.Default) {
                                    val tSave = System.nanoTime()
                                    s.saveDirtyTilesAndClear(batch)
                                    for ((_, tile) in batch) tile.clearOptionalArrays()
                                    max(1L, (System.nanoTime() - tSave) / 1_000_000)
                                }
                                val optAfter = optArrCount()
                                for ((k, tile) in batch) {
                                    val keyPacked = k.pack()
                                    tile.dirty = false
                                    pendingFlush.remove(keyPacked)
                                    pendingSinceNs.remove(keyPacked)
                                    maybeEvictColdTile(keyPacked, tile, reason = "post-flush")
                                }
                                // >>> Corrigido: evite coerceAtLeast(Int) → use max(1L, ...)
                                val dtMs: Long = max(1L, (System.nanoTime() - t0) / 1_000_000)
                                val mb = bytes / (1024.0 * 1024.0)
                                cntSaved.addAndGet(tilesCount.toLong())
                                cntBatches.incrementAndGet()
                                Log.d(
                                    TAG_FLUSH,
                                    "worker#$wid SAVED tiles=$tilesCount bytes=%.2fMB dt=%dms save=%dms optArr=$optBefore->$optAfter qNow=${flushQueue.size} pending=${pendingFlush.size}"
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
                    logMem(force = true)
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }

    // ===== Render (para overlay) =====
    @Volatile private var renderMode: HotVizMode = HotVizMode.COBERTURA

    fun buildOrGetBitmapFor(tx: Int, ty: Int): Bitmap? = buildOrGetBitmapFor(tx, ty, 1)

    fun buildOrGetBitmapFor(tx: Int, ty: Int, stride: Int): Bitmap? {
        val key = TileKey(tx, ty).pack()
        val tile = tiles[key] ?: return null
        val rev = tile.rev

        val normalizedStride = stride.coerceAtLeast(1)
        val blockSize = normalizedStride.coerceAtMost(tileSize)
        val outputSize = max(1, ceil(tileSize.toDouble() / blockSize).toInt())
        val totalPixels = outputSize * outputSize

        bmpLru.get(key, blockSize, rev)?.let {
            cntBmpReuse.incrementAndGet()
            return it
        }

        val (bmp, reusedFromPool) = BitmapLRU.obtain(outputSize, outputSize)
        if (reusedFromPool) {
            cntBmpReuse.incrementAndGet()
        } else {
            cntBmpAlloc.incrementAndGet()
        }

        ensureRenderBufferCapacity(totalPixels)
        val buffer = renderBuf


        when (renderMode) {
            HotVizMode.COBERTURA -> fillCoverageColors(tile, blockSize, buffer)
            HotVizMode.SOBREPOSICAO -> fillOverlapColors(tile, blockSize, buffer)
            HotVizMode.TAXA -> fillRateColors(tile, blockSize, totalPixels, buffer)
            HotVizMode.VELOCIDADE -> fillSpeedColors(tile, blockSize, totalPixels, buffer)
            HotVizMode.SECOES -> fillSectionsColors(tile, blockSize, totalPixels, buffer)
        }

        uploadRenderBuffer(buffer, bmp, outputSize, outputSize)

        bmpLru.put(key, blockSize, rev, bmp)
        return bmp
    }

    private fun uploadRenderBuffer(buffer: IntArray, bmp: Bitmap, width: Int, height: Int) {
        var row = 0
        while (row < height) {
            val offset = row * width
            bmp.setPixels(buffer, offset, width, 0, row, width, 1)
            row++
        }
    }

    private fun fillCoverageColors(tile: TileData, blockSize: Int, buffer: IntArray) {        val counts = tile.count
        val size = tileSize
        forEachBlock(blockSize) { x0, x1, y0, y1, dstIndex ->
            var maxCount = 0
            var y = y0
            while (y < y1) {
                var idx = y * size + x0
                for (x in x0 until x1) {
                    val c = counts[idx].toInt() and 0xFF
                    if (c > maxCount) maxCount = c
                    idx++
                }
                y++
            }
            val v = when {
                maxCount == 0 -> 0
                maxCount == 1 -> 0x8022AA22.toInt()
                else -> 0xCCFF2222.toInt()
            }
            buffer[dstIndex] = v
        }
    }

    private fun fillOverlapColors(tile: TileData, blockSize: Int, buffer: IntArray) {
        val counts = tile.count
        val size = tileSize
        forEachBlock(blockSize) { x0, x1, y0, y1, dstIndex ->
            var overlap = false
            var y = y0
            loop@ while (y < y1) {
                var idx = y * size + x0
                for (x in x0 until x1) {
                    val c = counts[idx].toInt() and 0xFF
                    if (c > 1) {
                        overlap = true
                        break@loop
                    }
                    idx++
                }
                y++
            }
            val v = if (overlap) 0xCCFF2222.toInt() else 0
            buffer[dstIndex] = v
        }
    }

    private fun fillRateColors(tile: TileData, blockSize: Int, outPixels: Int, buffer: IntArray) {
        val rates = tile.rate ?: run {
            fillTransparent(outPixels, buffer)
            return
        }
        val size = tileSize
        forEachBlock(blockSize) { x0, x1, y0, y1, dstIndex ->
            var sum = 0f
            var count = 0
            var y = y0
            while (y < y1) {
                var idx = y * size + x0
                for (x in x0 until x1) {
                    sum += rates[idx]
                    count++
                    idx++
                }
                y++
            }
            val avg = if (count > 0) sum / count else 0f
            val t = (avg / 100f).coerceIn(0f, 1f)
            val g = (t * 255).toInt()
            val b = 255 - g
            buffer[dstIndex] = (0xFF shl 24) or (0 shl 16) or (g shl 8) or b
        }
    }

    private fun fillSpeedColors(tile: TileData, blockSize: Int, outPixels: Int, buffer: IntArray) {
        val speeds = tile.speed ?: run {
            fillTransparent(outPixels, buffer)
            return
        }
        val size = tileSize
        forEachBlock(blockSize) { x0, x1, y0, y1, dstIndex ->
            var sum = 0f
            var count = 0
            var y = y0
            while (y < y1) {
                var idx = y * size + x0
                for (x in x0 until x1) {
                    sum += speeds[idx]
                    count++
                    idx++
                }
                y++
            }
            val avg = if (count > 0) sum / count else 0f
            val t = (avg / 20f).coerceIn(0f, 1f)
            val r = (t * 255).toInt()
            val g = 255 - r
            buffer[dstIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 0
        }
    }

    private fun fillSectionsColors(tile: TileData, blockSize: Int, outPixels: Int, buffer: IntArray) {
        val sections = tile.sections ?: run {
            fillTransparent(outPixels, buffer)
            return
        }
        val size = tileSize
        forEachBlock(blockSize) { x0, x1, y0, y1, dstIndex ->
            var mask = 0
            var y = y0
            while (y < y1) {
                var idx = y * size + x0
                for (x in x0 until x1) {
                    mask = mask or sections[idx]
                    idx++
                }
                y++
            }

            val v = when {
                mask == 0 -> 0
                mask and 1 != 0 -> 0xFF0066FF.toInt()
                mask and 2 != 0 -> 0xFF00CC66.toInt()
                mask and 4 != 0 -> 0xFFFFBB00.toInt()
                else -> 0xFF999999.toInt()
            }
            buffer[dstIndex] = v
        }
    }

    private fun fillTransparent(outPixels: Int, buffer: IntArray) {
        val limit = min(outPixels, buffer.size)
        buffer.fill(0, 0, limit)
    }

    private inline fun forEachBlock(
        blockSize: Int,
        crossinline consumer: (x0: Int, x1: Int, y0: Int, y1: Int, dstIndex: Int) -> Unit
    ) {
        val step = blockSize.coerceAtLeast(1)
        val size = tileSize
        var dst = 0
        var y = 0
        while (y < size) {
            val yEnd = min(y + step, size)
            var x = 0
            while (x < size) {
                val xEnd = min(x + step, size)
                consumer(x, xEnd, y, yEnd, dst)
                dst++
                x += step
            }
            y += step        }
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
        resetRenderBufferForTileSize(tileSize)
        projection = ProjectionHelper(originLat, originLon)
        clearCoverage()
        for (st in snap.tiles) {
            val td = TileData(tileSize, st.count, st.sections, st.rate, st.speed, st.lastStrokeId, st.frontStamp, st.layerMask).also { it.rev = st.rev }
            val key = TileKey(st.tx, st.ty).pack()
            accumulateRestoredMetrics(key, st.count, st.sections, st.rate)
            tiles[key] = td
            cntTileAlloc.incrementAndGet()
        }
        invalidateTiles(); logMem()
    }

    fun importTilesFromStore(tilesFromStore: Collection<StoreTile>) {
        clearCoverage()
        if (tilesFromStore.isEmpty()) {
            invalidateTiles()
            logMem()
            return
        }
        for (st in tilesFromStore) {
            val td = tileFromStore(st)
            val key = TileKey(st.tx, st.ty).pack()
            tiles[key] = td
            dataLru.put(key, td)
            cntTileAlloc.incrementAndGet()
        }
        recomputeMetricsFromTiles()
        invalidateTiles()
        logMem()
    }

    private fun recomputeMetricsFromTiles() {
        totalOncePx = 0
        totalOverlapPx = 0
        sectionPx.fill(0)
        rateSumBySection.fill(0.0)
        rateCountBySection.fill(0)
        rateSumByArea = 0.0
        rateCountByArea = 0L

        for (tile in tiles.values) {
            val counts = tile.count
            val sections = tile.sections
            val rate = tile.rate
            for (i in counts.indices) {
                val c = counts[i].toInt() and 0xFF
                if (c == 0) continue
                totalOncePx++
                if (c > 1) totalOverlapPx += (c - 1).toLong()

                val mask = sections?.get(i) ?: 0
                if (mask != 0) {
                    var bits = mask
                    while (bits != 0) {
                        val lsb = bits and -bits
                        val idx = Integer.numberOfTrailingZeros(lsb)
                        if (idx in sectionPx.indices) sectionPx[idx]++
                        bits = bits xor lsb
                    }
                }

                val rateValue = rate?.get(i)
                if (rateValue != null && !rateValue.isNaN()) {
                    rateSumByArea += rateValue
                    rateCountByArea++
                    val secIdx = Integer.numberOfTrailingZeros(if (mask != 0) mask else 1)
                    if (secIdx in rateSumBySection.indices) {
                        rateSumBySection[secIdx] += rateValue
                        rateCountBySection[secIdx]++
                    }
                }
            }
        }
    }


    // ===== Utilitários =====
    private fun pack(tx: Int, ty: Int): Long = (tx.toLong() shl 32) xor (ty.toLong() and 0xffffffffL)
    private fun computeFrontLength(widthM: Double): Double = max(FRONT_LEN_W_FACTOR * widthM, FRONT_LEN_MIN_PX * resolutionM)

    private fun heapJavaMB(): Double {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return used / (1024.0 * 1024.0)
    }


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

    private fun optArrCount(): Int {
        var c = 0
        for (t in tiles.values) {
            if (t.sections != null) c++
            if (t.rate != null) c++
            if (t.speed != null) c++
            if (t.lastStrokeId != null) c++
        }
        return c
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
        val withLast: Int,
        val approxBytesTiles: Long,
        val approxBytesBitmaps: Long,
        val flushQueueSize: Int,
    )

    private fun memBreakdown(): MemBreakdown {
        var wf = 0; var ws = 0; var wr = 0; var wv = 0; var wl = 0
        var sumBytes = 0L
        for (t in tiles.values) {
            if (t.frontStamp != null) wf++
            if (t.sections != null) ws++
            if (t.rate != null) wr++
            if (t.speed != null) wv++
            if (t.lastStrokeId != null) wl++
            sumBytes += estimateTileBytes(t)
        }
        val bmpBytes = estimateBmpLruBytes()
        return MemBreakdown(
            tiles = tiles.size,
            withFront = wf,
            withSections = ws,
            withRate = wr,
            withSpeed = wv,
            withLast = wl,
            approxBytesTiles = sumBytes,
            approxBytesBitmaps = bmpBytes,
            flushQueueSize = flushQueue.size
        )
    }

    fun logMem(tag: String = TAG_MEM, force: Boolean = false) {
        val now = System.nanoTime()
        val since = (now - lastMemLogNs) / 1_000_000
        if (!force && since < MEM_LOG_INTERVAL_MS && lastMemLogNs != 0L) return
        lastMemLogNs = now

        val ds = debugStats()
        val mb = memBreakdown()
        val tilesMB = mb.approxBytesTiles / (1024.0 * 1024.0)
        val bmpPoolMB = mb.approxBytesBitmaps / (1024.0 * 1024.0)
        val heapMB = heapJavaMB()
        val nativeMB = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        val pssMB = Debug.getPss() / 1024.0
        val optArrays = mb.withFront + mb.withSections + mb.withRate + mb.withSpeed + mb.withLast

        val enq = cntEnqueued.get(); val sav = cntSaved.get()
        val dEnq = enq - lastEnqLogged; val dSav = sav - lastSavedLogged
        lastEnqLogged = enq; lastSavedLogged = sav

        val warnFlags = buildString {
            if (heapMB > WARN_HEAP_MB) append(" HEAP!")
            if (nativeMB > WARN_NATIVE_MB) append(" NATIVE!")
            if (tilesMB > WARN_TILES_MB) append(" TILES!")
            if (bmpPoolMB > WARN_BMPS_MB) append(" BMPS!")
        }

        Log.d(tag,
            "tileSize=${ds.tileSize} res=${"%.2f".format(ds.resolutionM)} " +
                    "HOTr=${ds.hotRadius} dataTiles=${ds.tilesDataCount} HOT=${ds.hotCount} VIZ=${ds.vizCount} " +
                    "bmpLRU=${ds.bmpLruSize} dataLRU=${ds.dataLruSize} bmpPoolMB=${"%.1f".format(bmpPoolMB)} " +
                    "flushQ=${mb.flushQueueSize} pending=${pendingFlush.size} | " +
                    "layers(front/sec/rate/spd/last)=${mb.withFront}/${mb.withSections}/${mb.withRate}/${mb.withSpeed}/${mb.withLast} optArr=$optArrays " +
                    "memTiles=${"%.1f".format(tilesMB)}MB heap=${"%.1f".format(heapMB)}MB native=${"%.1f".format(nativeMB)}MB pss=${"%.1f".format(pssMB)}MB | " +
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