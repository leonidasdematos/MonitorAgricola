// com/example/monitoragricola/raster/store/RasterTileDao.kt
package com.example.monitoragricola.raster.store

import androidx.room.*

data class RasterTileRow(
    val rev: Int,
    val layerMask: Int,
    val payload: ByteArray
)

@Dao
interface RasterTileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTiles(tiles: List<RasterTileEntity>)

    @Query("SELECT * FROM raster_tiles WHERE jobId = :jobId AND tx = :tx AND ty = :ty LIMIT 1")
    suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity?

    @Query("DELETE FROM raster_tiles WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}
