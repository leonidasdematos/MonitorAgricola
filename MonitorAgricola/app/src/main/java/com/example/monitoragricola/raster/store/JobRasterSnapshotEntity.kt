package com.example.monitoragricola.raster.store

import androidx.room.*

@Entity(tableName = "job_raster_snapshots")
data class JobRasterSnapshotEntity(
    @PrimaryKey val jobId: Long,
    val payload: ByteArray,          // GZIP do snapshot binário (meta+tiles)
    val updatedAt: Long
)

@Dao
interface JobRasterSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: JobRasterSnapshotEntity)

    @Query("SELECT payload FROM job_raster_snapshots WHERE jobId = :jobId LIMIT 1")
    suspend fun selectPayload(jobId: Long): ByteArray?

    @Query("DELETE FROM job_raster_snapshots WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}
