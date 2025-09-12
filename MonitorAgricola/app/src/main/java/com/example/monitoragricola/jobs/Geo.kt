// com/example/monitoragricola/jobs/Geo.kt
package com.example.monitoragricola.jobs

import kotlin.math.*

object Geo {
    /** Distância em metros entre dois pares lat/lon (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return R * c
    }
}