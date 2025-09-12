package com.example.monitoragricola.jobs.db

import androidx.room.*

// com/example/monitoragricola/jobs/db/JobRaster.kt
/*@Entity(
    tableName = "job_raster_meta",
    primaryKeys = ["jobId"]
)
data class JobRasterMetaEntity(
    val jobId: Long,
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int
)

@Entity(
    tableName = "job_raster_tiles",
    primaryKeys = ["jobId", "tx", "ty"],
    indices = [Index("jobId")]
)
data class JobRasterTileEntity(
    val jobId: Long,
    val tx: Int,
    val ty: Int,
    // Formato compacto; pode ser zlib/gzip do seu tile “count/sections/rate/frontStamp”
    val payload: ByteArray
)

@Dao
interface JobRasterDao {
    @Transaction
    @Query("SELECT * FROM job_raster_meta WHERE jobId = :jobId")
    suspend fun getMeta(jobId: Long): JobRasterMetaEntity?

    @Query("SELECT * FROM job_raster_tiles WHERE jobId = :jobId")
    suspend fun getTiles(jobId: Long): List<JobRasterTileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: JobRasterMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTiles(tiles: List<JobRasterTileEntity>)

    @Query("DELETE FROM job_raster_meta WHERE jobId = :jobId")
    suspend fun deleteMeta(jobId: Long)

    @Query("DELETE FROM job_raster_tiles WHERE jobId = :jobId")
    suspend fun deleteTiles(jobId: Long)

    @Transaction
    suspend fun replaceAll(jobId: Long, meta: JobRasterMetaEntity, tiles: List<JobRasterTileEntity>) {
        deleteTiles(jobId); deleteMeta(jobId)
        upsertMeta(meta); if (tiles.isNotEmpty()) upsertTiles(tiles)
    }

    @Query("DELETE FROM job_raster_tiles WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}
*/