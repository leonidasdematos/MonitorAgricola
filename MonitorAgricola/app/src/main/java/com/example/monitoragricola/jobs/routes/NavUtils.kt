// com/example/monitoragricola/jobs/routes/NavUtils.kt
package com.example.monitoragricola.jobs.routes

import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.operation.distance.DistanceOp

object NavUtils {
    fun nearestLineAndPoint(pointXY: Coordinate, lines: List<LineString>): Triple<LineString, Coordinate, Double>? {
        var best: Triple<LineString, Coordinate, Double>? = null
        for (ls in lines) {
            val distOp = DistanceOp(ls, GeometryFactory().createPoint(pointXY))
            val pair = distOp.closestPoints()
            val d = pair[0].distance(pair[1])
            if (best == null || d < best.third) best = Triple(ls, pair[0], d)
        }
        return best
    }
}
