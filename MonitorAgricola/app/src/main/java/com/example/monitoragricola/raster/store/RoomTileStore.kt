package com.example.monitoragricola.raster.store

import android.util.LruCache
import android.util.Log
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.TileStore
import com.example.monitoragricola.raster.StoreTile as EngineStoreTile
import com.example.monitoragricola.raster.RasterSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.LinkedHashSet


// Alias do StoreTile de storage (payload serializado)
import com.example.monitoragricola.raster.StoreTile as StoreStoreTile

class RoomTileStore(
    private val db: RasterDatabase,
    private val jobId: Long,
    private val maxCacheTiles: Int = 16) : TileStore {

    private val dao = db.rasterTileDao()

    // cache guarda apenas o payload comprimido para minimizar uso de heap
    private val cache = object : LruCache<TileKey, ByteArray>(maxCacheTiles) {
        override fun sizeOf(key: TileKey, value: ByteArray): Int = 1
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * loadTile é síncrona no engine → usar runBlocking(Dispatchers.IO).
     * Decodifica payload (GZIP) e mapeia StoreTile de storage -> engine.
     */
    override fun loadTile(tx: Int, ty: Int): EngineStoreTile? {
        val key = TileKey(tx, ty)
        cache.get(key)?.let { bytes ->
            val st: StoreStoreTile = TileCodec.decode(bytes).storeTile
            return EngineStoreTile(
                tx = st.tx,
                ty = st.ty,
                rev = st.rev,
                layerMask = st.layerMask,
                count = st.count,
                sections = st.sections,
                rate = st.rate,
                speed = st.speed,
                lastStrokeId = st.lastStrokeId,
                frontStamp = st.frontStamp
            )
        }
        return runBlocking(Dispatchers.IO) {
            val e = dao.getTile(jobId, tx, ty) ?: return@runBlocking null

            // Decodifica payload para o StoreTile de STORAGE
            cache.put(key, e.payload)

            val st: StoreStoreTile = TileCodec.decode(e.payload).storeTile

            // Mapeia para o StoreTile do ENGINE (exposto pela interface)
            val eng = EngineStoreTile(
                tx = st.tx,
                ty = st.ty,
                rev = st.rev,
                layerMask = st.layerMask,
                count = st.count,
                sections = st.sections,
                rate = st.rate,
                speed = st.speed,
                lastStrokeId = st.lastStrokeId,
                frontStamp = st.frontStamp
            )

            logMem()
            eng
        }
    }

    /**
     * Worker do engine já é coroutine → aqui é suspend normal.
     * Monta StoreTile de STORAGE para serializar, e faz upsert em IO.
     */
    override suspend fun saveDirtyTilesAndClear(batch: List<Pair<TileKey, TileData>>) {
        if (batch.isEmpty()) return

        val now = System.currentTimeMillis()
        val entities = ArrayList<RasterTileEntity>(batch.size)

        // Construção e encode podem ser CPU-bound, mas manter simples:
        for ((key, tile) in batch) {
            val ts = tile.tileSize

            // Monte o StoreTile de STORAGE com os buffers atuais do TileData
            val st = StoreStoreTile(
                tx = key.tx,
                ty = key.ty,
                rev = tile.rev,
                layerMask = tile.layerMask,
                count = tile.count,
                sections = tile.sections,          // pode ser null
                rate = tile.rate,                  // pode ser null
                speed = tile.speed,                // pode ser null
                lastStrokeId = tile.lastStrokeId,  // pode ser null
                frontStamp = tile.frontStamp       // pode ser null
            )

            val payload = TileCodec.encode(st, ts)

            entities += RasterTileEntity(
                jobId = jobId,
                tx = key.tx,
                ty = key.ty,
                rev = tile.rev,
                tileSize = ts,
                layerMask = tile.layerMask,
                payload = payload,
                updatedAt = now
            )

            // remove do cache para liberar arrays associados
            // atualiza cache com payload comprimido
            cache.put(key, payload)        }

        // I/O de fato em dispatcher de IO
        withContext(Dispatchers.IO) {
            dao.upsertTiles(entities)
        }
    }

    override fun snapshot(meta: RasterSnapshot) {
        // Room-based implementation does not persist snapshots yet
    }

    override fun restore(): RasterSnapshot? = null

    override fun clear() = runBlocking(Dispatchers.IO) {
        dao.deleteByJob(jobId)
        cache.evictAll()
    }

    /**
     * Prefetch assíncrono (IO) e cache em memória.
     */
    fun prefetchTiles(keys: Iterable<TileKey>) {
        scope.launch {
            for (key in keys) {
                if (cache.get(key) != null) continue
                val e = dao.getTile(jobId, key.tx, key.ty) ?: continue

                cache.put(key, e.payload)

            }
        }
    }

    suspend fun preloadTiles(engine: RasterCoverageEngine, keys: Collection<TileKey>) {
        if (keys.isEmpty()) {
            withContext(Dispatchers.Default) { engine.importTilesFromStore(emptyList()) }
            return
        }

        val tiles = withContext(Dispatchers.IO) {
            val unique = LinkedHashSet(keys)
            val list = ArrayList<StoreStoreTile>(unique.size)
            for (key in unique) {
                val entity = dao.getTile(jobId, key.tx, key.ty) ?: continue
                cache.put(key, entity.payload)
                val decoded = TileCodec.decode(entity.payload).storeTile
                list += decoded
            }
            list
        }

        withContext(Dispatchers.Default) {
            engine.importTilesFromStore(tiles)
        }
    }


    /** Opcional: log do tamanho do cache em memória */
    fun logMem(tag: String = "RoomTileStore") {
        Log.d(tag, "cacheSize=${cache.size()}")
    }
}
