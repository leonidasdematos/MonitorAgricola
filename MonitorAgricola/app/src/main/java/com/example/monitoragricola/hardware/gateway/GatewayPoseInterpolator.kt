package com.example.monitoragricola.hardware.gateway

import android.os.SystemClock
import com.example.monitoragricola.gps.api.GpsPose
import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Realiza interpolação temporal das poses recebidas do gateway para suavizar
 * o movimento no mapa quando a frequência de amostragem é baixa.
 */
class GatewayPoseInterpolator(
    private val smoothingTimeConstantSeconds: Double = 0.35,
    private val maxExtrapolationSeconds: Double = 0.7,
) {
    private var lastPose: GpsPose? = null

    private var smoothedLat = 0.0
    private var smoothedLon = 0.0
    private var hasSmoothedPosition = false

    private var smoothedHeadingRad = 0.0
    private var hasSmoothedHeading = false

    private var lastFrameRealtime = 0L
    private var hasFrameTime = false

    fun reset() {
        lastPose = null
        hasSmoothedPosition = false
        hasSmoothedHeading = false
        lastFrameRealtime = SystemClock.elapsedRealtime()
        hasFrameTime = false
    }

    fun onPose(pose: GpsPose) {
        lastPose = pose
        if (!hasSmoothedPosition) {
            smoothedLat = pose.latitude
            smoothedLon = pose.longitude
            hasSmoothedPosition = true
        }
        if (!hasSmoothedHeading && pose.headingDeg.isFinite()) {
            smoothedHeadingRad = normalizeRadians(Math.toRadians(pose.headingDeg))
            hasSmoothedHeading = true
        }
    }

    fun current(nowMillis: Long): InterpolatedPose? {
        val pose = lastPose ?: return if (hasSmoothedPosition) {
            InterpolatedPose(GeoPoint(smoothedLat, smoothedLon), headingDegreesOrNull())
        } else {
            null
        }

        val predicted = predictPose(pose, nowMillis)
        val nowRealtime = SystemClock.elapsedRealtime()
        val dtSeconds = if (hasFrameTime) {
            val delta = (nowRealtime - lastFrameRealtime).coerceAtLeast(0L)
            min(delta, MAX_FRAME_GAP_MS) / 1000.0
        } else {
            0.0
        }
        lastFrameRealtime = nowRealtime
        hasFrameTime = true

        val alpha = if (dtSeconds <= 0.0) 1.0 else 1 - exp(-dtSeconds / smoothingTimeConstantSeconds)

        if (!hasSmoothedPosition) {
            smoothedLat = predicted.latitude
            smoothedLon = predicted.longitude
            hasSmoothedPosition = true
        } else {
            smoothedLat += alpha * (predicted.latitude - smoothedLat)
            smoothedLon += alpha * (predicted.longitude - smoothedLon)
        }

        if (pose.headingDeg.isFinite()) {
            val targetRad = normalizeRadians(Math.toRadians(pose.headingDeg))
            if (!hasSmoothedHeading) {
                smoothedHeadingRad = targetRad
                hasSmoothedHeading = true
            } else {
                val delta = wrapAngle(targetRad - smoothedHeadingRad)
                smoothedHeadingRad = normalizeRadians(smoothedHeadingRad + alpha * delta)
            }
        }

        return InterpolatedPose(
            GeoPoint(smoothedLat, smoothedLon),
            headingDegreesOrNull()
        )
    }

    private fun headingDegreesOrNull(): Double? = if (hasSmoothedHeading) {
        val deg = Math.toDegrees(smoothedHeadingRad)
        if (deg.isFinite()) normalizeHeadingDeg(deg) else null
    } else {
        null
    }

    private fun predictPose(pose: GpsPose, nowMillis: Long): GeoPoint {
        val deltaMillis = (nowMillis - pose.timestampMillis).coerceAtLeast(0L)
        val clamped = min(deltaMillis, (maxExtrapolationSeconds * 1000).toLong())
        if (clamped == 0L || pose.speedMps <= MIN_SPEED_THRESHOLD) {
            return GeoPoint(pose.latitude, pose.longitude)
        }
        val distance = pose.speedMps * (clamped / 1000.0)
        return advance(pose.latitude, pose.longitude, pose.headingDeg, distance)
    }

    private fun advance(latDeg: Double, lonDeg: Double, headingDeg: Double, distanceMeters: Double): GeoPoint {
        val headingRad = normalizeRadians(Math.toRadians(headingDeg))
        val northComponent = distanceMeters * cos(headingRad)
        val eastComponent = distanceMeters * sin(headingRad)

        val latRad = Math.toRadians(latDeg)
        val dLat = northComponent / EARTH_RADIUS_M
        val dLon = if (abs(cos(latRad)) < 1e-6) 0.0 else eastComponent / (EARTH_RADIUS_M * cos(latRad))

        val newLat = latDeg + Math.toDegrees(dLat)
        val newLon = lonDeg + Math.toDegrees(dLon)
        return GeoPoint(newLat, newLon)
    }

    private fun normalizeHeadingDeg(value: Double): Double {
        var deg = value % 360.0
        if (deg < 0.0) deg += 360.0
        return deg
    }

    private fun wrapAngle(angleRad: Double): Double {
        var a = angleRad
        while (a <= -PI) a += TWO_PI
        while (a > PI) a -= TWO_PI
        return a
    }

    private fun normalizeRadians(angleRad: Double): Double {
        var a = angleRad % TWO_PI
        if (a < 0.0) a += TWO_PI
        return a
    }

    data class InterpolatedPose(
        val position: GeoPoint,
        val headingDeg: Double?,
    )

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val MIN_SPEED_THRESHOLD = 0.01
        private const val MAX_FRAME_GAP_MS = 500L
        private const val TWO_PI = (2.0 * Math.PI)
    }
}