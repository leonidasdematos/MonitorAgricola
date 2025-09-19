package com.example.monitoragricola.raster.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Metadados persistidos do raster de um trabalho.
 */
@Entity(tableName = "job_raster_metadata")
data class JobRasterMetadataEntity(
    @PrimaryKey val jobId: Long,
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int
)

data class JobRasterMetadata(
    val jobId: Long,
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int
)

fun JobRasterMetadataEntity.toDomain() = JobRasterMetadata(
    jobId = jobId,
    originLat = originLat,
    originLon = originLon,
    resolutionM = resolutionM,
    tileSize = tileSize
)

fun JobRasterMetadata.toEntity() = JobRasterMetadataEntity(
    jobId = jobId,
    originLat = originLat,
    originLon = originLon,
    resolutionM = resolutionM,
    tileSize = tileSize
)

@Dao
interface JobRasterMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: JobRasterMetadataEntity)

    @Query("SELECT * FROM job_raster_metadata WHERE jobId = :jobId LIMIT 1")
    suspend fun select(jobId: Long): JobRasterMetadataEntity?

    @Query("DELETE FROM job_raster_metadata WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}