// com/example/monitoragricola/jobs/routes/RoutesRepository.kt
package com.example.monitoragricola.jobs.routes

import com.example.monitoragricola.jobs.routes.db.*

class RoutesRepository(private val dao: JobRouteDao) {
    suspend fun create(route: JobRouteEntity): Long = dao.insertRoute(route)
    suspend fun saveLines(lines: List<JobRouteLineEntity>) = dao.insertLines(lines)
    suspend fun lines(routeId: Long) = dao.getLines(routeId)
    suspend fun activeRoutes(jobId: Long) = dao.getActiveRoutes(jobId)
    suspend fun deleteRoute(routeId: Long) = dao.deleteRoute(routeId)
    suspend fun deleteLines(routeId: Long) = dao.deleteLines(routeId)
}
