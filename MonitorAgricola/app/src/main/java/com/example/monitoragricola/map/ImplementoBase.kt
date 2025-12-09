package com.example.monitoragricola.map

import android.os.SystemClock
import android.util.Log
import com.example.monitoragricola.raster.RasterCoverageEngine
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

abstract class ImplementoBase(
    private val rasterEngine: RasterCoverageEngine,           // ⬅️ antes era AreaManager
    protected var distanciaAntena: Float = 0f,
    protected var offsetLateral: Float = 0f,
    protected var offsetLongitudinal: Float = 0f
) : Implemento {

    protected var running = false
    @Volatile private var rasterSuspended = false


    private var paintModel: PaintModel = PaintModel.ENTRADA_COMPENSADA
    fun setPaintModel(model: PaintModel) { paintModel = model }
    fun getPaintModel(): PaintModel = paintModel

    // ===== cache dos endpoints da barra (lat/lon) =====
    private var lastBarP1: GeoPoint? = null
    private var lastBarP2: GeoPoint? = null

    fun getImplementBarEndpoints(): Pair<GeoPoint, GeoPoint>? =
        if (lastBarP1 != null && lastBarP2 != null) lastBarP1!! to lastBarP2!! else null

    // ===== cache do centro do implemento =====
    private val EPS_IMPL = 0.01
    private var lastHeadingRad: Double? = null
    private var lastImplCenterLL: GeoPoint? = null
    fun getImplementCenter(): GeoPoint? = lastImplCenterLL

    private var previousImplLocal: Coordinate? = null

    @Volatile private var latestExternalTelemetry: ExternalTelemetry? = null
    private val telemetryInterpolator = ExternalTelemetryInterpolator()

    protected abstract fun getWorkWidthMeters(): Float

    open fun updateOffsets(
        distanciaAntena: Float? = null,
        offsetLateral: Float? = null,
        offsetLongitudinal: Float? = null
    ) {
        distanciaAntena?.let { this.distanciaAntena = it }
        offsetLateral?.let   { this.offsetLateral   = it }
        offsetLongitudinal?.let { this.offsetLongitudinal = it }
    }

    override fun start() { running = true }
    override fun stop()  { running = false }

    /**
     * Suspende temporariamente a pintura no raster, mantendo o restante da geometria
     * atualizada (centro e barra).
     */
    fun setRasterSuspended(suspended: Boolean) {
        rasterSuspended = suspended
    }

    override fun updatePosition(
        last: GeoPoint?,
        current: GeoPoint,
        headingDeg: Float?,
        speedMps: Float?,
    ) {
        val telemetry = telemetryInterpolator.current() ?: return
        val implementPoint = telemetry.implementLatLon ?: return
        val headingRad = telemetry.implementHeadingRad ?: return

        val proj = ProjectionHelper(current.latitude, current.longitude)
        val curImplLocal = proj.toLocalMeters(implementPoint)
        val lastImplLocal = previousImplLocal ?: curImplLocal
        previousImplLocal = curImplLocal

        lastHeadingRad = headingRad
        implThetaRad = headingRad

        val fwdX = sin(headingRad)
        val fwdY = cos(headingRad)
        val rightX = fwdY
        val rightY = -fwdX

        val dImpl = hypot(curImplLocal.x - lastImplLocal.x, curImplLocal.y - lastImplLocal.y)
        val w = getWorkWidthMeters()

        if (running && !rasterSuspended && telemetry.isImplementActive && dImpl >= EPS_IMPL) {
            val lastImplLL = proj.toLatLon(lastImplLocal)
            val curImplLL = proj.toLatLon(curImplLocal)

            try {

                rasterEngine.paintStroke(
                    last = GeoPoint(lastImplLL.latitude, lastImplLL.longitude),
                    current = GeoPoint(curImplLL.latitude, curImplLL.longitude),
                    implementWidthMeters = w.toDouble(),
                    activeSectionsMask = telemetry.activeSectionsMask,
                    rateValue = telemetry.rateValue,
                    strokeRightX = rightX,
                    strokeRightY = rightY,
                )
            } catch (t: Throwable) {
                Log.e("ImplementoBase", "Falha ao pintar área (raster): ${t.message}")
            }
        }

        val half = (w / 2.0).toDouble()
        val p1Local = Coordinate(curImplLocal.x - half * rightX, curImplLocal.y - half * rightY)
        val p2Local = Coordinate(curImplLocal.x + half * rightX, curImplLocal.y + half * rightY)
        val p1LL = proj.toLatLon(p1Local)
        val p2LL = proj.toLatLon(p2Local)
        lastBarP1 = GeoPoint(p1LL.latitude, p1LL.longitude)
        lastBarP2 = GeoPoint(p2LL.latitude, p2LL.longitude)
        val curImplLL = proj.toLatLon(curImplLocal)
        lastImplCenterLL = GeoPoint(curImplLL.latitude, curImplLL.longitude)
    }

    fun updateBarPreview(last: GeoPoint?, current: GeoPoint, headingDeg: Float?) {
        val telemetry = telemetryInterpolator.current() ?: return
        val implementPoint = telemetry.implementLatLon ?: return
        val headingRad = telemetry.implementHeadingRad ?: return

        val proj = ProjectionHelper(current.latitude, current.longitude)
        val curImplLocal = proj.toLocalMeters(implementPoint)
        val lastImplLocal = previousImplLocal ?: curImplLocal
        previousImplLocal = curImplLocal

        lastHeadingRad = headingRad
        implThetaRad = headingRad

        val fwdX = sin(headingRad)
        val fwdY = cos(headingRad)
        val rightX = fwdY
        val rightY = -fwdX

        val w = getWorkWidthMeters()
        val half = (w / 2.0).toDouble()

        val p1Local = Coordinate(curImplLocal.x - half * rightX, curImplLocal.y - half * rightY)
        val p2Local = Coordinate(curImplLocal.x + half * rightX, curImplLocal.y + half * rightY)
        val p1LL = proj.toLatLon(p1Local)
        val p2LL = proj.toLatLon(p2Local)
        lastBarP1 = GeoPoint(p1LL.latitude, p1LL.longitude)
        lastBarP2 = GeoPoint(p2LL.latitude, p2LL.longitude)

        val curImplLL = proj.toLatLon(curImplLocal)
        lastImplCenterLL = GeoPoint(curImplLL.latitude, curImplLL.longitude)

    }

    override fun getStatus(): Map<String, Any> = mapOf(
        "distanciaAntena"    to distanciaAntena,
        "offsetLateral"      to offsetLateral,
        "offsetLongitudinal" to offsetLongitudinal,
        "width"              to getWorkWidthMeters(),
        "paintModel"         to paintModel.name.lowercase()
    )

    protected var implThetaRad: Double? = null

    /**
     * O monitor depende exclusivamente da pose explícita enviada pelo gateway.
     * Não há fallback, inferência ou cálculo substitutivo. O gateway é a autoridade única de telemetria.
     */

    data class ExternalTelemetry(
        val isImplementActive: Boolean,
        val activeSectionsMask: Int,
        val rateValue: Float?,
        val timestampMillis: Long,
        val mode: String?,                     // "fixed" | "articulated"
        val implementLatLon: GeoPoint?,        // do JSON
        val implementHeadingRad: Double?,      // heading_deg convertido para rad
    )

    open fun updateExternalTelemetry(telemetry: ExternalTelemetry?) {
        latestExternalTelemetry = telemetry
        telemetryInterpolator.onTelemetry(telemetry)
        if (telemetry == null) {
            previousImplLocal = null
        }
    }

    open class RuntimeState(var thetaRad: Double? = null, var telemetry: ExternalTelemetry? = null)
    open fun exportRuntimeState(): RuntimeState = RuntimeState(thetaRad = implThetaRad, telemetry = latestExternalTelemetry)
    open fun importRuntimeState(state: RuntimeState?) {
        implThetaRad = state?.thetaRad
        latestExternalTelemetry = state?.telemetry
        telemetryInterpolator.onTelemetry(state?.telemetry)
    }

    private class ExternalTelemetryInterpolator(
        private val maxSegmentDurationMillis: Long = 1000L,
        private val fallbackSegmentDurationMillis: Long = 200L,
    ) {
        private var previous: ExternalTelemetry? = null
        private var current: ExternalTelemetry? = null
        private var segmentStartRealtime = 0L
        private var segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS

        fun onTelemetry(telemetry: ExternalTelemetry?) {
            if (telemetry == null) {
                reset()
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (current == null) {
                previous = telemetry
                current = telemetry
                segmentStartRealtime = now
                segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
                return
            }

            val last = current!!
            previous = last
            current = telemetry
            segmentStartRealtime = now
            segmentDurationMillis = computeSegmentDuration(last, telemetry)
        }

        fun current(): ExternalTelemetry? {
            val cur = current ?: return null
            val prev = previous ?: cur

            val duration = segmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
            val elapsed = (SystemClock.elapsedRealtime() - segmentStartRealtime).coerceAtLeast(0L)
            val progress = if (duration <= 0L) 1.0 else (elapsed.toDouble() / duration).coerceIn(0.0, 1.0)

            if (progress <= 0.0) return cur
            if (progress >= 1.0) return cur

            val interpolatedPosition = interpolateGeoPoint(prev.implementLatLon, cur.implementLatLon, progress)
            val interpolatedHeading = interpolateAngle(prev.implementHeadingRad, cur.implementHeadingRad, progress)

            return ExternalTelemetry(
                isImplementActive = cur.isImplementActive,
                activeSectionsMask = cur.activeSectionsMask,
                rateValue = cur.rateValue,
                timestampMillis = cur.timestampMillis,
                mode = cur.mode,
                implementLatLon = interpolatedPosition,
                implementHeadingRad = interpolatedHeading,
                )
        }

        private fun computeSegmentDuration(previous: ExternalTelemetry, current: ExternalTelemetry): Long {
            val raw = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
            val maxDuration = maxSegmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
            val fallbackDuration = fallbackSegmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
            val duration = if (raw > 0L) raw.coerceAtMost(maxDuration) else fallbackDuration
            return duration.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        }

        private fun interpolateGeoPoint(prev: GeoPoint?, cur: GeoPoint?, progress: Double): GeoPoint? {
            prev ?: return cur
            cur ?: return prev
            val lat = prev.latitude + (cur.latitude - prev.latitude) * progress
            val lon = prev.longitude + (cur.longitude - prev.longitude) * progress
            return GeoPoint(lat, lon)
        }

        private fun interpolateAngle(prev: Double?, cur: Double?, progress: Double): Double? {
            prev ?: return cur
            cur ?: return prev
            val base = wrapAngleNear(prev, cur)
            val target = wrapAngleNear(cur, base)
            val interpolated = base + (target - base) * progress
            return wrapToSignedPi(interpolated)
        }

        private fun wrapAngleNear(value: Double, reference: Double): Double {
            var candidate = value
            var diff = candidate - reference
            while (diff > Math.PI) { candidate -= 2 * Math.PI; diff = candidate - reference }
            while (diff < -Math.PI) { candidate += 2 * Math.PI; diff = candidate - reference }
            return candidate
        }

        private fun wrapToSignedPi(value: Double): Double {
            var angle = value % (2 * Math.PI)
            if (angle <= -Math.PI) angle += 2 * Math.PI
            if (angle > Math.PI) angle -= 2 * Math.PI
            return angle
        }

        private fun reset() {
            previous = null
            current = null
            segmentStartRealtime = 0L
            segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
        }

        companion object {
            private const val MIN_SEGMENT_DURATION_MS = 16L
            private const val DEFAULT_SEGMENT_DURATION_MS = 200L
        }
    }
}
