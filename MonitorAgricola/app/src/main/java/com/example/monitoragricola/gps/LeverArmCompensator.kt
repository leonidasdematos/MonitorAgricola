package com.example.monitoragricola.gps

import kotlin.math.cos
import kotlin.math.sin

class LeverArmCompensator(
    antennaToImplementMeters: Double,
    lateralOffsetMeters: Double,
) {

    @Volatile
    private var longitudinalOffset = antennaToImplementMeters

    @Volatile
    private var lateralOffset = lateralOffsetMeters

    fun updateOffsets(longitudinal: Double, lateral: Double) {
        longitudinalOffset = longitudinal
        lateralOffset = lateral
    }

    fun compensate(x: Double, y: Double, headingRad: Double): Pair<Double, Double> {
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)
        val dx = -longitudinalOffset * cosH - lateralOffset * sinH
        val dy = -longitudinalOffset * sinH + lateralOffset * cosH
        return Pair(x + dx, y + dy)
    }
}