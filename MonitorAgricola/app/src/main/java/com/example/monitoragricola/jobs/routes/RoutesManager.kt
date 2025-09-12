// com/example/monitoragricola/jobs/routes/RoutesManager.kt
package com.example.monitoragricola.jobs.routes

import com.example.monitoragricola.jobs.routes.db.JobRouteEntity
import com.example.monitoragricola.jobs.routes.db.JobRouteLineEntity
import com.example.monitoragricola.jobs.routes.RoutesRepository

class RoutesManager(private val repo: RoutesRepository) {

    suspend fun createABRoute(
        jobId: Long,
        route: JobRouteEntity,
        lines: List<JobRouteLineEntity>
    ): Long {
        // salva rota
        val routeId = repo.create(route.copy(jobId = jobId))
        // salva linhas colocando o routeId real
        val fixed = lines.map { it.copy(routeId = routeId) }
        repo.saveLines(fixed)
        return routeId
    }

    suspend fun loadActive(jobId: Long) = repo.activeRoutes(jobId)
    suspend fun loadLines(routeId: Long) = repo.lines(routeId)
    suspend fun delete(routeId: Long) = repo.deleteRoute(routeId).also { repo.deleteLines(routeId) }
}
