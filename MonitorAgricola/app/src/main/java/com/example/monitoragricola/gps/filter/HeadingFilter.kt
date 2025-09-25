package com.example.monitoragricola.gps.filter

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

class HeadingFilter(
    private val vHeadingMin: Double,
    private val emaAlphaLowSpeed: Double,
    private val emaAlphaHighSpeed: Double,
    articulatedMode: Boolean,
    private val articulatedTurnLimit: Double = 35.0,
    private val sensorBlendWeight: Double = 0.15,
) {

    data class Result(
        val headingDeg: Double,
        val alphaUsed: Double,
    )

    private var articulatedMode = articulatedMode
    private var initialized = false
    private var lastHeading = 0.0
    private var lastTimestamp = 0L

    fun reset() {
        initialized = false
        lastHeading = 0.0
        lastTimestamp = 0L
    }

    fun setArticulatedMode(enabled: Boolean) {
        articulatedMode = enabled
    }

    fun update(
        vx: Double,
        vy: Double,
        speed: Double,
        timestampMillis: Long,
        stationaryHeadingDeg: Double?,
        sensorHeadingDeg: Double?,
    ): Result {
        val dtSec = if (lastTimestamp != 0L) ((timestampMillis - lastTimestamp) / 1000.0).coerceAtLeast(1e-3) else 0.0
        lastTimestamp = timestampMillis

        val velocityHeading = if (speed >= vHeadingMin && (abs(vx) + abs(vy)) > 1e-3) {
            Math.toDegrees(atan2(vy, vx))
        } else null

        var target = velocityHeading ?: stationaryHeadingDeg ?: lastHeading

        if (sensorHeadingDeg != null) {
            target = blendAngles(target, sensorHeadingDeg, sensorBlendWeight)
        }

        val alpha = when {
            velocityHeading == null -> emaAlphaLowSpeed * 0.5
            else -> {
                val diff = (speed - vHeadingMin).coerceAtLeast(0.0)
                val span = (emaAlphaHighSpeed - emaAlphaLowSpeed)
                val interp = (diff / 3.0).coerceIn(0.0, 1.0)
                emaAlphaLowSpeed + span * interp
            }
        }.let { base -> if (articulatedMode) base * 0.65 else base }

        if (!initialized) {
            lastHeading = normalize(target)
            initialized = true
            return Result(lastHeading, alpha)
        }

        val unwrappedTarget = unwrap(target, lastHeading)
        var newHeading = lastHeading + alpha * (unwrappedTarget - lastHeading)

        if (articulatedMode && dtSec > 0) {
            val maxDelta = articulatedTurnLimit * dtSec
            val delta = (newHeading - lastHeading)
            if (abs(delta) > maxDelta) {
                newHeading = lastHeading + maxDelta * sign(delta)
            }
        }

        lastHeading = normalize(newHeading)
        return Result(lastHeading, alpha)
    }

    private fun normalize(angle: Double): Double {
        var v = angle % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private fun unwrap(target: Double, reference: Double): Double {
        var diff = target - reference
        while (diff < -180.0) diff += 360.0
        while (diff > 180.0) diff -= 360.0
        return reference + diff
    }

    private fun blendAngles(a: Double, b: Double, weight: Double): Double {
        val aRad = Math.toRadians(a)
        val bRad = Math.toRadians(b)
        val x = (1 - weight) * cos(aRad) + weight * cos(bRad)
        val y = (1 - weight) * sin(aRad) + weight * sin(bRad)
        return Math.toDegrees(atan2(y, x))
    }
}