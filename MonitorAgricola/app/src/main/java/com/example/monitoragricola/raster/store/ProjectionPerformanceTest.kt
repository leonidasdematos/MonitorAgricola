/*package com.example.monitoragricola.raster

import com.example.monitoragricola.map.ProjectionHelper
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.system.measureTimeMillis

class ProjectionPerformanceTest {
    @Test
    fun compareAllocVsDirect() {
        val ph = ProjectionHelper(10.0, 20.0)
        val gp = GeoPoint(0.0, 0.0)
        val iterations = 100_000
        var acc = 0.0

        val alloc = measureTimeMillis {
            for (i in 0 until iterations) {
                val g = ph.toLatLon(Coordinate(i.toDouble(), i.toDouble()))
                acc += g.latitude
            }
        }

        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(10.0))
        val direct = measureTimeMillis {
            for (i in 0 until iterations) {
                localToGeo(10.0, 20.0, mPerDegLat, mPerDegLon, i.toDouble(), i.toDouble(), gp)
                acc += gp.latitude
            }
        }
        println("alloc=${alloc}ms direct=${direct}ms acc=$acc")
    }

    private fun localToGeo(
        lat0: Double,
        lon0: Double,
        mPerDegLat: Double,
        mPerDegLon: Double,
        xm: Double,
        ym: Double,
        out: GeoPoint
    ) {
        out.latitude = lat0 + (ym / mPerDegLat)
        out.longitude = lon0 + (xm / mPerDegLon)
    }
}*/