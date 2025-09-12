// com/example/monitoragricola/jobs/routes/CurveRouteGenerator.kt
package com.example.monitoragricola.jobs.routes

import com.example.monitoragricola.jobs.routes.db.JobRouteEntity
import com.example.monitoragricola.jobs.routes.db.JobRouteLineEntity
import com.example.monitoragricola.map.ProjectionHelper
import org.locationtech.jts.geom.*
import org.locationtech.jts.io.WKBWriter
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

class CurveRouteGenerator(
    private val gf: GeometryFactory = GeometryFactory()
) {
    private val wkb = WKBWriter()

    /**
     * Gera rota Curva a partir do trilho gravado.
     * Salva somente a linha central (idx=0) e a origem da projeção.
     */
    fun generateFromTrack(
        trackLatLon: List<Pair<Double, Double>>,
        spacingM: Double,
        simplifyToleranceM: Double = 0.10
    ): Pair<JobRouteEntity, List<JobRouteLineEntity>> {

        require(trackLatLon.size >= 2) { "Track pequeno: mínimo 2 pontos." }

        // Origem = ponto do meio do trilho
        val mid = trackLatLon[trackLatLon.size / 2]
        val originLat = mid.first
        val originLon = mid.second
        val proj = ProjectionHelper(originLat, originLon)

        // Lat/Lon -> XY (m)
        val coords = trackLatLon.map {
            val p = proj.toLocalMeters(org.osmdroid.util.GeoPoint(it.first, it.second))
            Coordinate(p.x, p.y)
        }.toTypedArray()

        // LineString + simplificação
        var ref = gf.createLineString(coords)
        if (simplifyToleranceM > 0.0) {
            ref = TopologyPreservingSimplifier.simplify(ref, simplifyToleranceM) as LineString
        }

        // Persistir apenas a central (idx = 0)
        val lines = mutableListOf<JobRouteLineEntity>().apply {
            add(
                JobRouteLineEntity(
                    routeId = 0L,
                    idx = 0,
                    wkbLine = wkb.write(ref)
                )
            )
        }

        val route = JobRouteEntity(
            id = 0,
            jobId = -1,
            type = RouteType.AB_CURVE,
            state = RouteState.ACTIVE,
            originLat = originLat,
            originLon = originLon,
            spacingM = spacingM.toFloat(),
            widthM = null,
            createdAt = System.currentTimeMillis()
        )

        return route to lines
    }
}
