/*
// com/example/monitoragricola/jobs/db/JobGeomDao.kt
package com.example.monitoragricola.jobs.db

import androidx.room.*

@Dao
interface JobGeomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(geom: JobGeomEntity)

    @Query("SELECT * FROM job_geom WHERE jobId = :jobId LIMIT 1")
    suspend fun get(jobId: Long): JobGeomEntity?

    @Query("DELETE FROM job_geom WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}
*/