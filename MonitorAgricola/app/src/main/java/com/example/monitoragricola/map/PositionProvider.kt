package com.example.monitoragricola.map

import org.osmdroid.util.GeoPoint

interface PositionProvider {
    fun start()
    fun stop()
    fun getCurrentPosition(): GeoPoint?
}
