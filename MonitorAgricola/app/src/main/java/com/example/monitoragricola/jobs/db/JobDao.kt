// com/example/monitoragricola/jobs/db/JobDao.kt
package com.example.monitoragricola.jobs.db

import androidx.room.*
import com.example.monitoragricola.jobs.JobState
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert
    suspend fun insert(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun delete(jobId: Long)

    @Query("SELECT * FROM jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE state = :state ORDER BY updatedAt DESC")
    fun observeByState(state: JobState): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    suspend fun get(jobId: Long): JobEntity?

    @Query("SELECT * FROM jobs WHERE state = 'ACTIVE' LIMIT 1")
    suspend fun getActive(): JobEntity?

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteById(jobId: Long)

}




