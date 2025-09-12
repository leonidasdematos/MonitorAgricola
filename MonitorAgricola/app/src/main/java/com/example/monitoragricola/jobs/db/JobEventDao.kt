// com/example/monitoragricola/jobs/db/JobEventDao.kt
package com.example.monitoragricola.jobs.db

import androidx.room.*

@Dao
interface JobEventDao {
    @Insert
    suspend fun insert(event: JobEventEntity): Long

    @Query("DELETE FROM job_events WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}