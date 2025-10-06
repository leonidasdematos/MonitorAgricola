package com.example.monitoragricola.hardware.gateway

import android.os.SystemClock
import com.example.monitoragricola.gps.api.GpsPose
import org.osmdroid.util.GeoPoint

/**
 * Realiza interpolação temporal das poses recebidas do gateway criando uma
 * animação contínua entre a penúltima e a última pose conhecida. Dessa forma
 * mantemos um pequeno atraso deliberado, permitindo atualizar o mapa em alta
 * taxa de quadros mesmo quando a taxa de poses é baixa.
 */
class GatewayPoseInterpolator(
    private val maxSegmentDurationSeconds: Double = 1.0,
    private val fallbackSegmentDurationSeconds: Double = 0.2,
) {
    private var previousPose: GpsPose? = null
    private var currentPose: GpsPose? = null
    private var lastHeadingOutputDeg: Double? = null


    private var segmentStartRealtime = 0L
    private var segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS

    fun reset() {
        previousPose = null
        currentPose = null
        segmentStartRealtime = 0L
        segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
        lastHeadingOutputDeg = null
    }

    fun onPose(pose: GpsPose) {
        val nowRealtime = SystemClock.elapsedRealtime()
        if (currentPose == null) {
            currentPose = pose
            previousPose = pose
            segmentStartRealtime = nowRealtime
            segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
            return
        }

        val last = currentPose!!
        previousPose = last
        currentPose = pose
        segmentStartRealtime = nowRealtime
        segmentDurationMillis = computeSegmentDuration(last, pose)
    }

    @Suppress("UNUSED_PARAMETER")
    fun current(nowMillis: Long): InterpolatedPose? {
        val current = currentPose ?: return null
        val previous = previousPose ?: current

        val duration = segmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        val elapsed = (SystemClock.elapsedRealtime() - segmentStartRealtime).coerceAtLeast(0L)
        val progress = if (duration <= 0L) 1.0 else (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)

        val lat = previous.latitude + (current.latitude - previous.latitude) * progress
        val lon = previous.longitude + (current.longitude - previous.longitude) * progress
        val heading = interpolateHeading(previous, current, progress)?.also {
            lastHeadingOutputDeg = it
        }
        return InterpolatedPose(
            GeoPoint(lat, lon),
            heading,
        )
    }

    private fun computeSegmentDuration(previous: GpsPose, current: GpsPose): Long {
        val maxDuration = (maxSegmentDurationSeconds * 1000).toLong().coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        val fallbackDuration = (fallbackSegmentDurationSeconds * 1000).toLong().coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        val rawDuration = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
        val duration = if (rawDuration > 0L) rawDuration.coerceAtMost(maxDuration) else fallbackDuration
        return duration.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
    }

    private fun interpolateHeading(previous: GpsPose, current: GpsPose, progress: Double): Double? {
        val previousHeading = previous.headingDeg.takeIf { it.isFinite() }
        val currentHeading = current.headingDeg.takeIf { it.isFinite() }

        return when {
            previousHeading == null && currentHeading == null -> null
            previousHeading == null -> normalizeHeadingForOutput(currentHeading!!)
            currentHeading == null -> normalizeHeadingForOutput(previousHeading)
            progress <= 0.0 -> normalizeHeadingForOutput(previousHeading)
            progress >= 1.0 -> normalizeHeadingForOutput(currentHeading)
            else -> {
                val reference = lastHeadingOutputDeg
                val base = reference?.let { wrapAngleNear(previousHeading, it) }
                    ?: wrapToSigned180(previousHeading)
                val target = wrapAngleNear(currentHeading, base)
                val interpolated = base + (target - base) * progress
                wrapToSigned180(interpolated)
            }
        }
    }

    private fun normalizeHeadingForOutput(value: Double): Double {
        val reference = lastHeadingOutputDeg
        return if (reference != null) wrapAngleNear(value, reference) else wrapToSigned180(value)
    }

    private fun wrapAngleNear(value: Double, reference: Double): Double {
        var candidate = value
        var diff = candidate - reference
        while (diff > 180.0) {
            candidate -= 360.0
            diff = candidate - reference
        }
        while (diff < -180.0) {
            candidate += 360.0
            diff = candidate - reference
        }
        return candidate
    }

    private fun wrapToSigned180(value: Double): Double {
        var deg = value % 360.0
        if (deg <= -180.0) deg += 360.0
        if (deg > 180.0) deg -= 360.0
        return deg
    }

    data class InterpolatedPose(
        val position: GeoPoint,
        val headingDeg: Double?,
    )

    companion object {
        private const val MIN_SEGMENT_DURATION_MS = 16L
        private const val DEFAULT_SEGMENT_DURATION_MS = 200L
    }
}