package com.example.monitoragricola.gps.filter

import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StationaryDetector(
    private val speedThreshold: Double,
    private val jitterThresholdM: Double,
    private val baseWindowSize: Int = 8,
) {

    data class Result(
        val isStationary: Boolean,
        val headingDeg: Double?,
    )

    private var anchorX = 0.0
    private var anchorY = 0.0
    private var hasAnchor = false
    private var lastStationary = false
    private val headings = ArrayDeque<Double>()

    fun reset() {
        hasAnchor = false
        lastStationary = false
        headings.clear()
    }

    fun update(
        x: Double,
        y: Double,
        vx: Double,
        vy: Double,
        speed: Double,
        articulatedMode: Boolean,
    ): Result {
        val window = if (articulatedMode) baseWindowSize * 2 else baseWindowSize
        val dt = hypot(vx, vy)
        val headingRad = if (dt > 1e-3) atan2(vy, vx) else null

        val jitter = if (hasAnchor) hypot(x - anchorX, y - anchorY) else 0.0
        val stationary = speed < speedThreshold && jitter <= jitterThresholdM

        if (stationary) {
            if (!hasAnchor) {
                anchorX = x
                anchorY = y
                hasAnchor = true
            }
            headingRad?.let {
                headings.addLast(it)
                while (headings.size > window) headings.removeFirst()
            }
        } else {
            hasAnchor = false
            headings.clear()
        }
        lastStationary = stationary

        val headingDeg = when {
            headings.isEmpty() -> headingRad?.let { Math.toDegrees(it) }
            else -> Math.toDegrees(circularMean())
        }

        return Result(stationary, headingDeg)
    }

    private fun circularMean(): Double {
        var sumSin = 0.0
        var sumCos = 0.0
        for (h in headings) {
            sumSin += sin(h)
            sumCos += cos(h)
        }
        return atan2(sumSin, sumCos)
    }
}