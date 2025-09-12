package com.example.monitoragricola.map

import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.cos

class ProjectionHelper(private val originLat: Double, private val originLon: Double) {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(originLat))

    // Já existente
    fun toLocalMeters(p: GeoPoint): Coordinate {
        val x = (p.longitude - originLon) * mPerDegLon
        val y = (p.latitude - originLat) * mPerDegLat
        return Coordinate(x, y)
    }

    // Conveniência (compatibilidade com chamadas por lat/lon)
    fun toLocalMeters(lat: Double, lon: Double): Coordinate =
        toLocalMeters(GeoPoint(lat, lon))

    fun toLatLon(c: Coordinate): GeoPoint {
        val lat = originLat + (c.y / mPerDegLat)
        val lon = originLon + (c.x / mPerDegLon)
        return GeoPoint(lat, lon)
    }

    // (Opcional) reancorar para reduzir distorção ao cobrir áreas muito grandes
    fun reanchor(newOriginLat: Double, newOriginLon: Double): ProjectionHelper =
        ProjectionHelper(newOriginLat, newOriginLon)
}