// com/example/monitoragricola/jobs/routes/db/JobRouteDao.kt
package com.example.monitoragricola.jobs.routes.db

import androidx.room.*

@Dao
interface JobRouteDao {
    @Insert suspend fun insertRoute(route: JobRouteEntity): Long
    @Update suspend fun updateRoute(route: JobRouteEntity)
    @Query("SELECT * FROM job_routes WHERE jobId = :jobId AND state = 'ACTIVE' ORDER BY id DESC")
    suspend fun getActiveRoutes(jobId: Long): List<JobRouteEntity>
    @Query("DELETE FROM job_routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: Long)

    @Insert suspend fun insertLines(lines: List<JobRouteLineEntity>)
    @Query("SELECT * FROM job_route_lines WHERE routeId = :routeId ORDER BY idx")
    suspend fun getLines(routeId: Long): List<JobRouteLineEntity>
    @Query("DELETE FROM job_route_lines WHERE routeId = :routeId")
    suspend fun deleteLines(routeId: Long)
}
