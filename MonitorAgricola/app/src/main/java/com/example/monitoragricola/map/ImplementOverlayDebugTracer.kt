package com.example.monitoragricola.map

import android.os.SystemClock
import android.util.Log
import java.util.Locale
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Helper to log suspicious jumps in the implement overlay so we can correlate
 * the gateway interpolation with the implement preview behaviour.
 */
class ImplementOverlayDebugTracer(private val enabled: Boolean) {
    private data class PreviewSnapshot(
        val hadLastFix: Boolean,
        val deltaMeters: Double?,
        val headingDeg: Float?,
        val status: Map<String, Any>,
        val timestamp: Long,
        val debugInfo: ImplementoBase.PreviewDebugInfo?,
        )

    private data class BarState(
        val p1: GeoPoint,
        val p2: GeoPoint,
        val center: GeoPoint?,
        val articulation: GeoPoint?,
        val tractor: GeoPoint,
        val lengthMeters: Double,
        val bearingDeg: Double,
        val timestamp: Long,
    )

    private var lastPreview: PreviewSnapshot? = null
    private var lastBar: BarState? = null

    fun onPreviewInput(
        impl: ImplementoBase,
        lastGps: GeoPoint?,
        currentGps: GeoPoint,
        headingDeg: Float?,
        debugInfo: ImplementoBase.PreviewDebugInfo?,
    ) {
        if (!enabled) return
        val delta = lastGps?.distanceToAsDouble(currentGps)
        val statusCopy = impl.getStatus().mapValues { it.value }
        lastPreview = PreviewSnapshot(
            hadLastFix = lastGps != null,
            deltaMeters = delta,
            headingDeg = headingDeg,
            status = statusCopy,
            timestamp = SystemClock.elapsedRealtime(),
            debugInfo = debugInfo,
            )
    }

    fun onRendererUpdate(
        bar: Pair<GeoPoint, GeoPoint>?,
        center: GeoPoint?,
        articulation: GeoPoint?,
        tractorPos: GeoPoint,
    ) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastBar

        if (bar == null) {
            if (prev != null) {
                Log.w(
                    TAG,
                    "Implement bar cleared unexpectedly; last length=${prev.lengthMeters.format2()}m, " +
                            "tractor shift=${tractorPos.distanceToAsDouble(prev.tractor).format2()}m. ${describePreview()}"
                )
            }
            lastBar = null
            return
        }

        val (p1, p2) = bar
        val length = p1.distanceToAsDouble(p2)
        val bearing = bearingBetween(p1, p2)
        val state = BarState(
            p1 = p1,
            p2 = p2,
            center = center,
            articulation = articulation,
            tractor = tractorPos,
            lengthMeters = length,
            bearingDeg = bearing,
            timestamp = now,
        )

        if (length < MIN_EXPECTED_LENGTH_METERS) {
            Log.w(
                TAG,
                "Implement bar length below threshold: ${length.format3()}m. ${describePreview()}"
            )
        }

        if (prev != null) {
            val bearingDiff = angularDiff(bearing, prev.bearingDeg)
            val tractorShift = tractorPos.distanceToAsDouble(prev.tractor)
            val centerShift = center?.let { c -> prev.center?.let { c.distanceToAsDouble(it) } }

            if (abs(bearingDiff) > HEADING_JUMP_THRESHOLD_DEG && tractorShift < TRACTOR_MOVEMENT_THRESHOLD_M) {
                val directSwap = swapAnalysis(state, prev)
                val swapSuspect = directSwap.swapped + SWAP_DISTANCE_EPS < directSwap.direct
                Log.w(
                    TAG,
                    "Large bar heading jump ${bearingDiff.format2()}° with tractor shift=${tractorShift.format2()}m " +
                            "and center shift=${centerShift?.format2()}m, swapSuspect=$swapSuspect " +
                            "(direct=${directSwap.direct.format2()}m vs swapped=${directSwap.swapped.format2()}m). " +
                            describePreview()
                )
            }

            val lengthDiff = abs(length - prev.lengthMeters)
            if (lengthDiff > LENGTH_JUMP_THRESHOLD_M) {
                Log.w(
                    TAG,
                    "Implement bar length changed ${lengthDiff.format2()}m (prev=${prev.lengthMeters.format2()}m, " +
                            "current=${length.format2()}m). ${describePreview()}"
                )
            }
        } else {
            // First frame after recreation: check if preview was provided recently.
            val previewAge = lastPreview?.let { now - it.timestamp }
            if (previewAge != null && previewAge > PREVIEW_STALE_THRESHOLD_MS) {
                Log.i(
                    TAG,
                    "Renderer updated without a recent preview (${previewAge}ms). ${describePreview()}"
                )
            }
        }

        lastBar = state
    }

    fun onOverlayCleared() {
        if (!enabled) return
        lastBar = null
        lastPreview = null
    }

    private fun describePreview(): String {
        val preview = lastPreview ?: return "preview=∅"
        val age = SystemClock.elapsedRealtime() - preview.timestamp
        val statusSummary = preview.status.entries.joinToString(
            prefix = "status={",
            postfix = "}",
            separator = ", "
        ) { (key, value) -> "$key=${formatAny(value)}" }
        val deltaText = preview.deltaMeters?.format2()?.let { "${it}m" } ?: "∅"
        val headingText = preview.headingDeg?.toDouble()?.format2() ?: "∅"
        val debugText = preview.debugInfo?.let { info ->
            val forward = info.forwardVector.formatVec()
            val right = info.rightVector.formatVec()
            val theta = info.implThetaRad?.let { String.format("%.2f°", Math.toDegrees(it)) } ?: "∅"
            "debug={disp=${info.displacementMeters.format3()}m, fwd=${info.forwardSource.name.lowercase(Locale.ROOT)}$forward, " +
                    "right=${info.rightSource.name.lowercase(Locale.ROOT)}$right, articulated=${info.usedArticulatedCenters}, " +
                    "axisActive=${info.articulationAxisActive}, paint=${info.paintModel.name.lowercase(Locale.ROOT)}, " +
                    "headingIn=${info.headingDegInput?.toDouble()?.format2() ?: "∅"}, lastHeadingRad=${info.lastHeadingRad?.format2() ?: "∅"}, " +
                    "implTheta=${theta}}"
        } ?: "debug=∅"
        return "previewAge=${age}ms, hadLast=${preview.hadLastFix}, Δ=${deltaText}, heading=${headingText}, ${statusSummary}, ${debugText}"    }

    private fun formatAny(value: Any): String = when (value) {
        is Number -> value.toDouble().format2()
        else -> value.toString()
    }

    private fun Pair<Double, Double>.formatVec(): String = "(${first.format2()}, ${second.format2()})"

    private fun swapAnalysis(current: BarState, previous: BarState): SwapAnalysis {
        val direct = current.p1.distanceToAsDouble(previous.p1) + current.p2.distanceToAsDouble(previous.p2)
        val swapped = current.p1.distanceToAsDouble(previous.p2) + current.p2.distanceToAsDouble(previous.p1)
        return SwapAnalysis(direct, swapped)
    }

    private data class SwapAnalysis(val direct: Double, val swapped: Double)


    private fun bearingBetween(start: GeoPoint, end: GeoPoint): Double {
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val lat2 = Math.toRadians(end.latitude)
        val lon2 = Math.toRadians(end.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    private fun angularDiff(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff < -180) diff += 360.0
        if (diff > 180) diff -= 360.0
        return diff
    }

    private fun Double.format2(): String = String.format("%.2f", this)
    private fun Double.format3(): String = String.format("%.3f", this)

    companion object {
        private const val TAG = "ImplOverlayDebug"
        private const val MIN_EXPECTED_LENGTH_METERS = 0.05
        private const val HEADING_JUMP_THRESHOLD_DEG = 25.0
        private const val TRACTOR_MOVEMENT_THRESHOLD_M = 0.25
        private const val LENGTH_JUMP_THRESHOLD_M = 0.5
        private const val PREVIEW_STALE_THRESHOLD_MS = 200L
        private const val SWAP_DISTANCE_EPS = 0.2

    }
}