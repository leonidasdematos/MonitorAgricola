package com.example.monitoragricola.raster.store

import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.TileStore
import com.example.monitoragricola.raster.StoreTile
import kotlinx.coroutines.runBlocking

class TileStoreRoom(
    private val db: RasterRoomDb,
    private val jobId: Long
) : TileStore {

    private val dao = db.rasterTileDao()

    /**
     * loadTile é síncrona no engine → use runBlocking, sem withTransaction.
     */
    override fun loadTile(tx: Int, ty: Int): StoreTile? = runBlocking {
        val e = dao.getTile(jobId, tx, ty) ?: return@runBlocking null
        val d = TileCodec.decode(e.payload)

        val st = d.storeTile
        return@runBlocking StoreTile(
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
    }

    /**
     * Worker do engine já é coroutine → aqui é suspend normal.
     */
    override suspend fun saveDirtyTilesAndClear(batch: List<Pair<TileKey, TileData>>) {
        if (batch.isEmpty()) return

        val entities = ArrayList<RasterTileEntity>(batch.size)
        val now = System.currentTimeMillis()

        for ((key, tile) in batch) {
            val ts = tile.size

            // Monte o StoreTile com os buffers atuais do TileData
            val st = com.example.monitoragricola.raster.store.StoreTile(
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

        dao.upsertTiles(entities)
    }
}