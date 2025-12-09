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
    private var lastHitchLL: GeoPoint? = null

    fun getImplementBarEndpoints(): Pair<GeoPoint, GeoPoint>? =
        if (lastBarP1 != null && lastBarP2 != null) lastBarP1!! to lastBarP2!! else null

    // ===== cache do centro do implemento =====
    private val EPS_IMPL = 0.01
    private var lastHeadingRad: Double? = null
    private var lastImplCenterLL: GeoPoint? = null
    fun getImplementCenter(): GeoPoint? = lastImplCenterLL
    fun getImplementHitch(): GeoPoint? = lastHitchLL

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
        val geom = computeGeometry(current, headingDeg) ?: return

        if (running && !rasterSuspended && telemetryInterpolator.current()?.isImplementActive == true &&
            geom.deltaSinceLast >= EPS_IMPL
        ) {

            try {
                rasterEngine.paintStroke(
                    last = geom.lastImplLL,
                    current = geom.curImplLL,
                    implementWidthMeters = geom.workWidth,
                    activeSectionsMask = geom.telemetry.activeSectionsMask,
                    rateValue = geom.telemetry.rateValue,
                    strokeRightX = geom.rightX,
                    strokeRightY = geom.rightY,
                )
            } catch (t: Throwable) {
                Log.e("ImplementoBase", "Falha ao pintar área (raster): ${t.message}")
            }
        }

        lastBarP1 = geom.barP1
        lastBarP2 = geom.barP2
        lastImplCenterLL = geom.curImplLL
        lastHitchLL = geom.hitchLL
    }

    fun updateBarPreview(last: GeoPoint?, current: GeoPoint, headingDeg: Float?) {
        val geom = computeGeometry(current, headingDeg) ?: return

        lastBarP1 = geom.barP1
        lastBarP2 = geom.barP2
        lastImplCenterLL = geom.curImplLL
        lastHitchLL = geom.hitchLL

    }

    /** Parâmetros locais de articulação (A = antena→engate, B = engate→centro implemento). */
    protected open fun getArticulationParameters(): ArticulationParameters? = null

    /**
     * Calcula a geometria atual do implemento a partir da telemetria simplificada.
     * Convenção: heading_deg 0° = norte, cresce sentido horário.
     * Vetor forward = (sin(th), cos(th)); vetor right = (cos(th), -sin(th)).
     */
    private fun computeGeometry(current: GeoPoint, headingDeg: Float?): GeometryResult? {
        val telemetry = telemetryInterpolator.current() ?: return null
        val implementPoint = telemetry.implementLatLon ?: return null

        val isArticulatedMode = telemetry.mode?.lowercase() == "articulated" || paintModel == PaintModel.ARTICULADO
        val headingRad = when {
            isArticulatedMode -> telemetry.implementHeadingRad
            else -> telemetry.implementHeadingRad ?: headingDeg?.let { Math.toRadians(it.toDouble()) }
        } ?: return null

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

        val workWidth = getWorkWidthMeters().toDouble()
        val half = workWidth / 2.0

        val p1Local = Coordinate(curImplLocal.x - half * rightX, curImplLocal.y - half * rightY)
        val p2Local = Coordinate(curImplLocal.x + half * rightX, curImplLocal.y + half * rightY)

        val p1LL = proj.toLatLon(p1Local)
        val p2LL = proj.toLatLon(p2Local)

        val curImplLL = proj.toLatLon(curImplLocal)
        val lastImplLL = proj.toLatLon(lastImplLocal)

        val articulation = if (isArticulatedMode) getArticulationParameters() else null
        val hitchLocal = articulation?.let { params ->
            Coordinate(
                curImplLocal.x - params.hitchToImplementMeters * fwdX,
                curImplLocal.y - params.hitchToImplementMeters * fwdY,
            )
        }
        val hitchLL = hitchLocal?.let { coord ->
            proj.toLatLon(coord).let { ll -> GeoPoint(ll.latitude, ll.longitude) }
        }

        val deltaSinceLast = hypot(curImplLocal.x - lastImplLocal.x, curImplLocal.y - lastImplLocal.y)

        return GeometryResult(
            telemetry = telemetry,
            curImplLocal = curImplLocal,
            lastImplLocal = lastImplLocal,
            curImplLL = GeoPoint(curImplLL.latitude, curImplLL.longitude),
            lastImplLL = GeoPoint(lastImplLL.latitude, lastImplLL.longitude),
            barP1 = GeoPoint(p1LL.latitude, p1LL.longitude),
            barP2 = GeoPoint(p2LL.latitude, p2LL.longitude),
            hitchLL = hitchLL,
            rightX = rightX,
            rightY = rightY,
            deltaSinceLast = deltaSinceLast,
            workWidth = workWidth,
        )
    }

    protected data class ArticulationParameters(
        val antennaToHitchMeters: Double,
        val hitchToImplementMeters: Double,
    )

    private data class GeometryResult(
        val telemetry: ExternalTelemetry,
        val curImplLocal: Coordinate,
        val lastImplLocal: Coordinate,
        val curImplLL: GeoPoint,
        val lastImplLL: GeoPoint,
        val barP1: GeoPoint,
        val barP2: GeoPoint,
        val hitchLL: GeoPoint?,
        val rightX: Double,
        val rightY: Double,
        val deltaSinceLast: Double,
        val workWidth: Double,
    )

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
