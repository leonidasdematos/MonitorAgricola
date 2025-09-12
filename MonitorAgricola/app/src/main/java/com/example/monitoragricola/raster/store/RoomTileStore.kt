
package com.example.monitoragricola.raster.store

import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.TileKey
import kotlinx.coroutines.runBlocking

class TileStoreRoom(
    private val rasterDb: RasterDatabase,
    private val jobId: Long
) : TileStore {

    private val dao = rasterDb.rasterTileDao()

    /**
     * loadTile é síncrona no engine → use runBlocking, sem withTransaction.
     */
    override fun loadTile(tx: Int, ty: Int): StoreTile? = runBlocking {
        val e = dao.getTile(jobId, tx, ty) ?: return@runBlocking null
        val d = TileCodec.decode(e.payload)

        return@runBlocking StoreTile(
            tx = tx,
            ty = ty,
            rev = e.rev,
            count = d.count,
            sections = d.sections,
            rate = d.rate,
            speed = d.speed,
            lastStrokeId = d.lastStrokeId ?: ShortArray(e.tileSize * e.tileSize),
            frontStamp = d.frontStamp,
            layerMask = e.layerMask
        )
    }
}

    /**
     * Worker do engine já é coroutine → aqui é suspend normal.
     */