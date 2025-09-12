// com/example/monitoragricola/raster/store/JobRasterDao.kt
package com.example.monitoragricola.raster.store

import androidx.room.*

data class JobRasterMeta(
    val jobId: Long,
    val metaJson: String
)

@Dao
interface JobRasterDao {

    // ===== GRAVAÇÃO / DELETE =====
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: JobRasterEntity)

    @Query("DELETE FROM job_raster WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)

    // ===== LEITURAS "MAGRAS" (sem BLOB) =====
    @Query("SELECT jobId, metaJson FROM job_raster WHERE jobId = :jobId LIMIT 1")
    suspend fun selectMeta(jobId: Long): JobRasterMeta?

    // ===== PAYLOAD ISOLADO =====
    @Query("SELECT payload FROM job_raster WHERE jobId = :jobId LIMIT 1")
    suspend fun selectPayload(jobId: Long): ByteArray?

    @Query("UPDATE job_raster SET payload = X'' WHERE jobId = :jobId")
    suspend fun clearPayload(jobId: Long)

    // (Opcional) checar existência/tamanho sem carregar tudo
    @Query("SELECT CASE WHEN payload IS NULL THEN 0 ELSE length(payload) END FROM job_raster WHERE jobId = :jobId LIMIT 1")
    suspend fun payloadLength(jobId: Long): Int?
}
