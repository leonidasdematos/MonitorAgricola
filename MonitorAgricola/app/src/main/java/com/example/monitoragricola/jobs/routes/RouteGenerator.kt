// com/example/monitoragricola/jobs/routes/RouteGenerator.kt
package com.example.monitoragricola.jobs.routes

import com.example.monitoragricola.jobs.routes.db.JobRouteEntity
import com.example.monitoragricola.jobs.routes.db.JobRouteLineEntity
import com.example.monitoragricola.map.ProjectionHelper
import org.locationtech.jts.geom.*
import org.locationtech.jts.io.WKBWriter
import kotlin.math.*

class RouteGenerator(
    private val geomFactory: GeometryFactory = GeometryFactory()
) {
    private val wkb = WKBWriter()

    data class BoundsMeters(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        fun diag(): Double = hypot(maxX - minX, maxY - minY)
    }

    /**
     * Gera rota AB (reta) salvando apenas metadados (A/B, origem, spacing).
     * As faixas são desenhadas on-the-fly pelo RouteRenderer.
     */
    fun generateAB(
        A_lat: Double, A_lon: Double,
        B_lat: Double, B_lon: Double,
        spacingM: Double,
        bounds: BoundsMeters
    ): Pair<JobRouteEntity, List<JobRouteLineEntity>> {

        // origem = meio de A e B
        val originLat = (A_lat + B_lat) / 2.0
        val originLon = (A_lon + B_lon) / 2.0
        val localProj = ProjectionHelper(originLat, originLon)

        // geo -> metros usando a MESMA origem
        val A = localProj.toLocalMeters(org.osmdroid.util.GeoPoint(A_lat, A_lon))
        val B = localProj.toLocalMeters(org.osmdroid.util.GeoPoint(B_lat, B_lon))
        val dx = B.x - A.x
        val dy = B.y - A.y
        val headingDeg = Math.toDegrees(atan2(dy, dx)).toFloat()

        val route = JobRouteEntity(
            id = 0,
            jobId = -1,
            type = RouteType.AB_STRAIGHT,
            state = RouteState.ACTIVE,
            originLat = originLat,
            originLon = originLon,
            spacingM = spacingM.toFloat(),
            widthM = null,
            createdAt = System.currentTimeMillis(),
            aLat = A_lat, aLon = A_lon,
            bLat = B_lat, bLon = B_lon,
            headingDeg = headingDeg
        )

        // Não persistimos linhas para AB
        return route to emptyList()
    }
}
