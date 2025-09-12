// com/example/monitoragricola/jobs/db/JobPointDao.kt
package com.example.monitoragricola.jobs.db

import androidx.room.*

@Dao
interface JobPointDao {
    @Insert
    suspend fun insertAll(points: List<JobPointEntity>)

    @Insert
    suspend fun insert(point: JobPointEntity): Long

    @Query("SELECT COUNT(*) FROM job_points WHERE jobId = :jobId")
    suspend fun count(jobId: Long): Int

    @Query("SELECT MAX(seq) FROM job_points WHERE jobId = :jobId")
    suspend fun maxSeq(jobId: Long): Int?

    @Query("DELETE FROM job_points WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)

    @Query("SELECT * FROM job_points WHERE jobId = :jobId ORDER BY seq LIMIT :limit OFFSET :offset")
    suspend fun loadPointsPaged(jobId: Long, limit: Int, offset: Int): List<JobPointEntity>

    @Insert
    suspend fun insertPointsBulk(points: List<JobPointEntity>)

    @Query("SELECT * FROM job_points WHERE jobId = :jobId AND seq BETWEEN :startSeq AND :endSeq ORDER BY seq ASC")
    suspend fun getPointsBetweenSeq(jobId: Long, startSeq: Int, endSeq: Int): List<JobPointEntity>


}
