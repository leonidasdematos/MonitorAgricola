package com.example.monitoragricola.raster.store

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
import java.util.concurrent.ConcurrentHashMap

// Alias do StoreTile de storage (payload serializado)
import com.example.monitoragricola.raster.StoreTile as StoreStoreTile

class RoomTileStore(
    private val db: RasterDatabase,
    private val jobId: Long
) : TileStore {

    private val dao = db.rasterTileDao()

    // cache guarda o StoreTile do ENGINE
    private val cache = ConcurrentHashMap<TileKey, EngineStoreTile>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * loadTile é síncrona no engine → usar runBlocking(Dispatchers.IO).
     * Decodifica payload (GZIP) e mapeia StoreTile de storage -> engine.
     */
    override fun loadTile(tx: Int, ty: Int): EngineStoreTile? {
        val key = TileKey(tx, ty)
        cache[key]?.let { return it }

        return runBlocking(Dispatchers.IO) {
            val e = dao.getTile(jobId, tx, ty) ?: return@runBlocking null

            // Decodifica payload para o StoreTile de STORAGE
            val decoded = TileCodec.decode(e.payload)
            val st: StoreStoreTile = decoded.storeTile

            // Mapeia para o StoreTile do ENGINE (exposto pela interface)
            val eng = EngineStoreTile(
                tx = tx,
                ty = ty,
                rev = e.rev,
                layerMask = e.layerMask,
                count = st.count,
                sections = st.sections,
                rate = st.rate,
                speed = st.speed,
                lastStrokeId = st.lastStrokeId,
                frontStamp = st.frontStamp
            )

            cache[key] = eng
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
                lastStrokeId = tile.lastStrokeId,  // ShortArray existente no TileData
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
        }

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
        cache.clear()
    }

    /**
     * Prefetch assíncrono (IO) e cache em memória.
     */
    fun prefetchTiles(keys: Iterable<TileKey>) {
        scope.launch {
            for (key in keys) {
                if (cache.containsKey(key)) continue
                val e = dao.getTile(jobId, key.tx, key.ty) ?: continue

                val decoded = TileCodec.decode(e.payload)
                val st: StoreStoreTile = decoded.storeTile

                val eng = EngineStoreTile(
                    tx = key.tx,
                    ty = key.ty,
                    rev = e.rev,
                    layerMask = e.layerMask,
                    count = st.count,
                    sections = st.sections,
                    rate = st.rate,
                    speed = st.speed,
                    lastStrokeId = st.lastStrokeId,
                    frontStamp = st.frontStamp
                )

                cache[key] = eng
            }
        }
    }
}
