package com.example.monitoragricola.gps.filter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class OutlierGate(
    private val params: Params,
) {

    data class Params(
        val maxJumpM: Double,
        val maxAccelerationMps2: Double,
        val maxTurnRateDegPerSec: Double,
    )

    data class Candidate(
        val x: Double,
        val y: Double,
        val timestampMillis: Long,
        val speedMps: Double,
    )

    data class Stats(
        var rejectedDistance: Int = 0,
        var rejectedAcceleration: Int = 0,
        var rejectedHeading: Int = 0,
    )

    private var lastCandidate: Candidate? = null
    private var lastHeadingRad: Double? = null
    val stats = Stats()

    fun reset() {
        lastCandidate = null
        lastHeadingRad = null
        stats.rejectedDistance = 0
        stats.rejectedAcceleration = 0
        stats.rejectedHeading = 0
    }

    fun evaluate(candidate: Candidate): Boolean {
        val last = lastCandidate ?: run {
            lastCandidate = candidate
            return true
        }

        val dtSec = ((candidate.timestampMillis - last.timestampMillis) / 1000.0).coerceAtLeast(1e-3)

        val dx = candidate.x - last.x
        val dy = candidate.y - last.y
        val dist = hypot(dx, dy)

        if (dist > params.maxJumpM && dtSec < 1.5) {
            stats.rejectedDistance++
            return false
        }

        val instSpeed = dist / dtSec
        val accel = (instSpeed - last.speedMps) / dtSec
        if (abs(accel) > params.maxAccelerationMps2) {
            stats.rejectedAcceleration++
            return false
        }

        val prevHeading = lastHeadingRad
        if (prevHeading != null && dist > 0.05) {
            val currentHeading = atan2(dy, dx)
            val headingDiffDeg = angleDiffDeg(Math.toDegrees(currentHeading), Math.toDegrees(prevHeading))
            val turnRate = abs(headingDiffDeg) / dtSec
            if (turnRate > params.maxTurnRateDegPerSec) {
                stats.rejectedHeading++
                return false
            }
            lastHeadingRad = currentHeading
        } else if (dist > 0.05) {
            lastHeadingRad = atan2(dy, dx)
        }

        lastCandidate = candidate.copy(speedMps = instSpeed)
        return true
    }

    private fun angleDiffDeg(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff < -180) diff += 360.0
        if (diff > 180) diff -= 360.0
        return diff
    }
}