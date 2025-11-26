package com.example.monitoragricola.map

import android.os.SystemClock
import android.util.Log
import com.example.monitoragricola.raster.RasterCoverageEngine
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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

    private val EPS_STEP = 0.01
    private val EPS_IMPL = 0.01

    private var lastHeadingRad: Double? = null

    @Volatile private var latestExternalTelemetry: ExternalTelemetry? = null
    private val telemetryInterpolator = ExternalTelemetryInterpolator()

    fun getImplementBarEndpoints(): Pair<GeoPoint, GeoPoint>? =
        if (lastBarP1 != null && lastBarP2 != null) lastBarP1!! to lastBarP2!! else null

    // ===== cache do centro do implemento e da articulação (lat/lon) =====
    private var lastImplCenterLL: GeoPoint? = null
    private var lastArticulLL: GeoPoint? = null
    fun getImplementCenter(): GeoPoint? = lastImplCenterLL
    fun getArticulationPoint(): GeoPoint? = lastArticulLL

    // --- estado/articulação persistente ---
    private var artA: Double? = null
    private var artB: Double? = null
    private var axisX: Double? = null
    private var axisY: Double? = null

    private var pendingArticulationLocal: Coordinate? = null
    protected fun rememberArticulationLocal(local: Coordinate?) {
        pendingArticulationLocal = local
    }

    data class PreviewDebugInfo(
        val displacementMeters: Double,
        val forwardVector: Pair<Double, Double>,
        val forwardSource: ForwardSource,
        val rightVector: Pair<Double, Double>,
        val rightSource: RightSource,
        val usedArticulatedCenters: Boolean,
        val articulationAxisActive: Boolean,
        val paintModel: PaintModel,
        val headingDegInput: Float?,
        val lastHeadingRad: Double?,
        val implThetaRad: Double?,
    ) {
        enum class ForwardSource { DISPLACEMENT, POSE_HEADING, CACHED_HEADING, DEFAULT_NORTH }
        enum class RightSource { FORWARD_PERP, DISPLACEMENT_PERP, ARTICULATION_AXIS, FALLBACK_PERP }
    }

    @Volatile private var lastPreviewDebug: PreviewDebugInfo? = null

    fun consumePreviewDebugInfo(): PreviewDebugInfo? = lastPreviewDebug.also { lastPreviewDebug = null }


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

    /** Para o modo ARTICULADO: subclasse pode sobrescrever e também chamar rememberArticulationLocal(...) */
    protected open fun computeArticulatedCenters(
        lastXY: Coordinate,
        curXY: Coordinate,
        fwdX: Double, fwdY: Double,
        rightX: Double, rightY: Double
    ): Pair<Coordinate, Coordinate>? = null

    /** Caminho rígido (offset fixo em relação à antena). */
    private fun computeRigidCenters(
        lastXY: Coordinate,
        curXY:  Coordinate,
        fwdX: Double, fwdY: Double,
        rightX: Double, rightY: Double
    ): Pair<Coordinate, Coordinate> {
        val longOffset = (distanciaAntena + offsetLongitudinal).toDouble()
        val dX = -longOffset * fwdX + offsetLateral.toDouble() * rightX
        val dY = -longOffset * fwdY + offsetLateral.toDouble() * rightY
        val lastImplLocal = Coordinate(lastXY.x + dX, lastXY.y + dY)
        val curImplLocal  = Coordinate(curXY.x  + dX, curXY.y  + dY)
        return lastImplLocal to curImplLocal
    }

    override fun start() { running = true }
    override fun stop()  { running = false }

    /**
     * Suspende temporariamente a pintura no raster, mantendo o restante da geometria
     * atualizada (centro, barra, articulação).
     */
    fun setRasterSuspended(suspended: Boolean) {
        rasterSuspended = suspended
    }

    private fun minHeadingStep(speedMps: Float?, hasExternalHeading: Boolean): Double {
        val speed = speedMps?.let { abs(it) } ?: return EPS_STEP
        return when {
            speed < 0.05f -> if (hasExternalHeading) Double.POSITIVE_INFINITY else 0.15
            speed < 0.2f -> 0.08
            speed < 0.5f -> 0.03
            else -> EPS_STEP
        }
    }

    override fun updatePosition(
        last: GeoPoint?,
        current: GeoPoint,
        headingDeg: Float?,
        speedMps: Float?,
    ) {

        if (last == null){ return }

        val proj = ProjectionHelper(current.latitude, current.longitude)
        val lastXY = proj.toLocalMeters(last)
        val curXY  = proj.toLocalMeters(current)

        val vx = curXY.x - lastXY.x
        val vy = curXY.y - lastXY.y
        val dist = hypot(vx, vy)

        // Atualize o heading SEMPRE que houver qualquer variação mensurável
        val headingRadFromPose = headingDeg?.takeIf { it.isFinite() }?.toDouble()?.let { Math.toRadians(it) }
        headingRadFromPose?.let { lastHeadingRad = it }

        val headingStep = minHeadingStep(speedMps, headingRadFromPose != null)
        val useDisplacementForHeading = dist >= headingStep
        if (useDisplacementForHeading) {
            lastHeadingRad = atan2(vx, vy)
        }

        val vectorThreshold = max(EPS_STEP, headingStep)
        val (fwdX, fwdY) = if (dist >= vectorThreshold) {
            (vx / dist) to (vy / dist)
        } else {
            // Passo muito pequeno: use o heading cacheado; se não houver, assuma norte
            val th = lastHeadingRad ?: 0.0

            sin(th) to cos(th)
        }
        val rightX = fwdY
        val rightY = -fwdX

        val telemetry = telemetryInterpolator.current()
        val articulationTelemetry = telemetry?.articulation
        val useGatewayArticulation = articulationTelemetry != null &&
                (paintModel == PaintModel.ARTICULADO || telemetry.mode?.equals("articulated", ignoreCase = true) == true)
        val articulatedPair = if (useGatewayArticulation) {
            articulationTelemetry?.let { art ->
                val currentImplLL = art.implementLatLon
                val currentImplLocal = currentImplLL?.let { proj.toLocalMeters(it) }
                    ?: run {
                        val x = art.implementLocalX
                        val y = art.implementLocalY
                        if (x != null && y != null) Coordinate(curXY.x + x, curXY.y + y) else null
                    }
                if (currentImplLocal != null) {
                    val previousImplLocal = lastImplCenterLL?.let { proj.toLocalMeters(it) } ?: currentImplLocal
                    art.jointLatLon?.let { jointLL ->
                        rememberArticulationLocal(proj.toLocalMeters(jointLL))
                    } ?: run {
                        val x = art.jointLocalX
                        val y = art.jointLocalY
                        if (x != null && y != null) {
                            rememberArticulationLocal(Coordinate(curXY.x + x, curXY.y + y))
                        }
                    }
                    art.thetaRad?.let { implThetaRad = it }

                    val axisXValue = art.axisX
                    val axisYValue = art.axisY
                    art.antennaToJointMeters?.toDouble()?.let { value -> artA = value }
                    art.jointToImplementMeters?.toDouble()?.let { value -> artB = value }
                    val aValue = artA
                    val bValue = artB
                    if (axisXValue != null && axisYValue != null && aValue != null && bValue != null) {
                        rememberArticulationState(aValue, bValue, axisXValue, axisYValue)
                    } else if (axisXValue != null && axisYValue != null) {
                        val norm = hypot(axisXValue, axisYValue).coerceAtLeast(1e-9)
                        this.axisX = axisXValue / norm
                        this.axisY = axisYValue / norm
                    }

                    previousImplLocal to currentImplLocal
                } else {
                    null
                }
            }
        } else {
            null
        }

        // Centros do implemento conforme o modo (continua atualizando mesmo pausado)
        val (lastImplLocal, curImplLocal) = when {
            articulatedPair != null -> articulatedPair
            paintModel == PaintModel.ARTICULADO ->
                computeArticulatedCenters(lastXY, curXY, fwdX, fwdY, rightX, rightY)
                    ?: run {
                        computeRigidCenters(lastXY, curXY, fwdX, fwdY, rightX, rightY)
                    }
            else -> computeRigidCenters(lastXY, curXY, fwdX, fwdY, rightX, rightY)
        }

        // Centro e articulação (cache)
        val curImplLL = proj.toLatLon(curImplLocal)
        lastImplCenterLL = GeoPoint(curImplLL.latitude, curImplLL.longitude)
        pendingArticulationLocal?.let { jLocal ->
            val jLL = proj.toLatLon(jLocal)
            lastArticulLL = GeoPoint(jLL.latitude, jLL.longitude)
            pendingArticulationLocal = null
        }

        if (telemetry != null) {
            val mode = telemetry.mode ?: paintModel.key
            val implFromGateway = articulationTelemetry?.implementLatLon
            Log.i(
                "ArticulationDebug",
                "mode=$mode tractor=(${current.latitude},${current.longitude}) " +
                        "implFromGateway=${implFromGateway?.let { "(${it.latitude},${it.longitude})" }} " +
                        "implUsed=(${curImplLL.latitude},${curImplLL.longitude}) paintModel=${paintModel.name.lowercase()}"
            )
        }

        var strokeRightOverride: Pair<Double, Double>? = null
        // Barra (sempre atualiza)
        val (barRightX, barRightY) = when (paintModel) {
            PaintModel.FIXO -> rightX to rightY
            PaintModel.ARTICULADO -> {
                val ax = axisX
                val ay = axisY
                if (ax != null && ay != null) {
                    val rx = ay
                    val ry = -ax
                    strokeRightOverride = rx to ry
                    rx to ry
                } else {
                    val dx = curImplLocal.x - lastImplLocal.x
                    val dy = curImplLocal.y - lastImplLocal.y
                    val d  = hypot(dx, dy)
                    val fallback = if (d >= EPS_IMPL) (dy / d) to (-dx / d) else (rightX to rightY)
                    strokeRightOverride = fallback
                    fallback
                }
            }
            else -> {
                val dx = curImplLocal.x - lastImplLocal.x
                val dy = curImplLocal.y - lastImplLocal.y
                val d  = hypot(dx, dy)
                if (d >= EPS_IMPL) (dy / d) to (-dx / d) else (rightX to rightY)
            }
        }

        // Passo do implemento (p/ decidir pintar)
        val dImpl = hypot(curImplLocal.x - lastImplLocal.x, curImplLocal.y - lastImplLocal.y)
        val w = getWorkWidthMeters()


        // Pintura raster só quando rodando
        val implementActive = telemetry?.isImplementActive ?: true

        if (running && !rasterSuspended && implementActive && dImpl >= EPS_IMPL) {
            val lastImplLL = proj.toLatLon(lastImplLocal)
            val curImplLL2 = proj.toLatLon(curImplLocal)
            val lastImpl = GeoPoint(lastImplLL.latitude, lastImplLL.longitude)
            val curImpl  = GeoPoint(curImplLL2.latitude,  curImplLL2.longitude)

            try {

                rasterEngine.paintStroke(
                    last = lastImpl,
                    current = curImpl,
                    implementWidthMeters = w.toDouble(),
                    activeSectionsMask = telemetry?.activeSectionsMask ?: 0,
                    rateValue = telemetry?.rateValue,
                    strokeRightX = strokeRightOverride?.first,
                    strokeRightY = strokeRightOverride?.second
                )

            } catch (t: Throwable) {
                Log.e("ImplementoBase", "Falha ao pintar área (raster): ${t.message}")
            }
        }

        val half = (w / 2.0).toDouble()
        val p1Local = Coordinate(curImplLocal.x - half * barRightX, curImplLocal.y - half * barRightY)
        val p2Local = Coordinate(curImplLocal.x + half * barRightX, curImplLocal.y + half * barRightY)
        val p1LL = proj.toLatLon(p1Local)
        val p2LL = proj.toLatLon(p2Local)
        lastBarP1 = GeoPoint(p1LL.latitude, p1LL.longitude)
        lastBarP2 = GeoPoint(p2LL.latitude, p2LL.longitude)
    }
    private fun Double.format3() = String.format("%.3f", this)



    fun updateBarPreview(last: GeoPoint?, current: GeoPoint, headingDeg: Float?) {
        if (last == null) return

        val w = getWorkWidthMeters()
        val half = (w / 2.0).toDouble()

        val proj = ProjectionHelper(current.latitude, current.longitude)
        val lastXY = proj.toLocalMeters(last)
        val curXY  = proj.toLocalMeters(current)

        var vx = curXY.x - lastXY.x
        var vy = curXY.y - lastXY.y
        var d  = hypot(vx, vy)

        val (fwdX, fwdY) = if (d >= 0.01) {
            vx /= d; vy /= d; vx to vy
        } else {
            val th = headingDeg?.toDouble()?.let { it * Math.PI / 180.0 } ?: lastHeadingRad
            if (th != null) {
                // sistema: fwdX=sin(th), fwdY=cos(th) (0° = norte)
                sin(th) to cos(th)
            } else {
                // último recurso: mantenha o right/fwd antigo para não “quebrar”
                0.0 to 1.0
            }
        }
        val rightX = fwdY
        val rightY = -fwdX
        if (paintModel == PaintModel.ARTICULADO) {
            Log.d("ARTIC", "d=%.4f headingDeg=%s lastHeadingRad=%s fwd=(%.3f,%.3f) right=(%.3f,%.3f)"
                .format(d, headingDeg, lastHeadingRad, fwdX, fwdY, rightX, rightY))
        }
        val (lastImplLocal, curImplLocal) = when (paintModel) {
            PaintModel.ARTICULADO ->
                computeArticulatedCenters(lastXY, curXY, fwdX, fwdY, rightX, rightY)
                    ?: run {
                        val longOffset = (distanciaAntena + offsetLongitudinal).toDouble()
                        val dX = -longOffset * fwdX + offsetLateral.toDouble() * rightX
                        val dY = -longOffset * fwdY + offsetLateral.toDouble() * rightY
                        Coordinate(lastXY.x + dX, lastXY.y + dY) to
                                Coordinate(curXY.x  + dX, curXY.y  + dY)
                    }
            else -> {
                val longOffset = (distanciaAntena + offsetLongitudinal).toDouble()
                val dX = -longOffset * fwdX + offsetLateral.toDouble() * rightX
                val dY = -longOffset * fwdY + offsetLateral.toDouble() * rightY
                Coordinate(lastXY.x + dX, lastXY.y + dY) to
                        Coordinate(curXY.x  + dX, curXY.y  + dY)
            }
        }

        // ângulo do implemento cacheado (para articulado)
        if (getPaintModel() == PaintModel.ARTICULADO) {
            val dx = curImplLocal.x - lastImplLocal.x
            val dy = curImplLocal.y - lastImplLocal.y
            val dd = hypot(dx, dy)
            if (dd >= 1e-3) {
                implThetaRad = kotlin.math.atan2(dy, dx)
            } else if (implThetaRad == null) {
                val th = (headingDeg ?: 0f).toDouble() * Math.PI / 180.0
                implThetaRad = th
            }
        }

        val (barRightX, barRightY) = when (paintModel) {
            PaintModel.FIXO -> rightX to rightY
            PaintModel.ARTICULADO -> {
                val ax = axisX
                val ay = axisY
                if (ax != null && ay != null) {
                    ay to -ax
                } else {
                    val dx = curImplLocal.x - lastImplLocal.x
                    val dy = curImplLocal.y - lastImplLocal.y
                    val dd = hypot(dx, dy)
                    if (dd >= 0.01) (dy / dd) to (-dx / dd) else (rightX to rightY)
                }
            }
            else -> {
                val dx = curImplLocal.x - lastImplLocal.x
                val dy = curImplLocal.y - lastImplLocal.y
                val dd = hypot(dx, dy)
                if (dd >= 0.01) (dy / dd) to (-dx / dd) else (rightX to rightY)
            }
        }

        val p1Local = Coordinate(curImplLocal.x - half * barRightX, curImplLocal.y - half * barRightY)
        val p2Local = Coordinate(curImplLocal.x + half * barRightX, curImplLocal.y + half * barRightY)
        val p1LL = proj.toLatLon(p1Local)
        val p2LL = proj.toLatLon(p2Local)
        lastBarP1 = GeoPoint(p1LL.latitude, p1LL.longitude)
        lastBarP2 = GeoPoint(p2LL.latitude, p2LL.longitude)

        val curImplLL = proj.toLatLon(curImplLocal)
        lastImplCenterLL = GeoPoint(curImplLL.latitude, curImplLL.longitude)

        pendingArticulationLocal?.let { jLocal ->
            val jLL = proj.toLatLon(jLocal)
            lastArticulLL = GeoPoint(jLL.latitude, jLL.longitude)
            pendingArticulationLocal = null
        }
    }

    protected fun rememberArticulationState(a: Double, b: Double, ax: Double, ay: Double) {
        artA = a; artB = b
        val n = hypot(ax, ay).coerceAtLeast(1e-9)
        axisX = ax / n
        axisY = ay / n
    }

    // Mapear seu PaintModel para EpochMode
    /* fun PaintModel.toEpochMode(): RasterCoverageEngine.EpochMode =
        when (this) {
            PaintModel.FIXO -> RasterCoverageEngine.EpochMode.FIXO
            PaintModel.ARTICULADO -> RasterCoverageEngine.EpochMode.ARTICULADO
            else -> RasterCoverageEngine.EpochMode.ENTRADA
        }*/


    override fun getStatus(): Map<String, Any> = mapOf(
        "distanciaAntena"    to distanciaAntena,
        "offsetLateral"      to offsetLateral,
        "offsetLongitudinal" to offsetLongitudinal,
        "width"              to getWorkWidthMeters(),
        "paintModel"         to paintModel.name.lowercase()
    )

    protected var implThetaRad: Double? = null

    data class ExternalTelemetry(
        val isImplementActive: Boolean,
        val activeSectionsMask: Int,
        val rateValue: Float?,
        val timestampMillis: Long,
        val mode: String? = null,
        val articulation: Articulation? = null,
    ) {
        data class Articulation(
            val antennaToJointMeters: Float?,
            val jointToImplementMeters: Float?,
            val antennaLocalX: Double?,
            val antennaLocalY: Double?,
            val jointLocalX: Double?,
            val jointLocalY: Double?,
            val implementLocalX: Double?,
            val implementLocalY: Double?,
            val jointLatLon: GeoPoint?,
            val implementLatLon: GeoPoint?,
            val axisX: Double?,
            val axisY: Double?,
            val thetaRad: Double?,
            val hasMotion: Boolean,
        )
    }

    open fun updateExternalTelemetry(telemetry: ExternalTelemetry?) {
        latestExternalTelemetry = telemetry
        telemetryInterpolator.onTelemetry(telemetry)
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

            if (progress <= 0.0 || cur.articulation?.hasMotion == false) return cur
            if (progress >= 1.0) return cur

            val interpolatedArticulation = interpolateArticulation(prev.articulation, cur.articulation, progress)

            return ExternalTelemetry(
                isImplementActive = cur.isImplementActive,
                activeSectionsMask = cur.activeSectionsMask,
                rateValue = cur.rateValue,
                timestampMillis = cur.timestampMillis,
                mode = cur.mode,
                articulation = interpolatedArticulation,
            )
        }

        private fun computeSegmentDuration(previous: ExternalTelemetry, current: ExternalTelemetry): Long {
            val raw = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0L)
            val maxDuration = maxSegmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
            val fallbackDuration = fallbackSegmentDurationMillis.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
            val duration = if (raw > 0L) raw.coerceAtMost(maxDuration) else fallbackDuration
            return duration.coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        }

        private fun interpolateArticulation(
            previous: ExternalTelemetry.Articulation?,
            current: ExternalTelemetry.Articulation?,
            progress: Double,
        ): ExternalTelemetry.Articulation? {
            if (current == null) return null
            previous ?: return current

            val axisX = interpolateDouble(previous.axisX, current.axisX, progress)
            val axisY = interpolateDouble(previous.axisY, current.axisY, progress)
            val norm = if (axisX != null && axisY != null) kotlin.math.hypot(axisX, axisY).coerceAtLeast(1e-9) else null
            val normalizedAxisX = norm?.let { axisX!! / it } ?: current.axisX
            val normalizedAxisY = norm?.let { axisY!! / it } ?: current.axisY

            return ExternalTelemetry.Articulation(
                antennaToJointMeters = current.antennaToJointMeters,
                jointToImplementMeters = current.jointToImplementMeters,
                antennaLocalX = interpolateDouble(previous.antennaLocalX, current.antennaLocalX, progress),
                antennaLocalY = interpolateDouble(previous.antennaLocalY, current.antennaLocalY, progress),
                jointLocalX = interpolateDouble(previous.jointLocalX, current.jointLocalX, progress),
                jointLocalY = interpolateDouble(previous.jointLocalY, current.jointLocalY, progress),
                implementLocalX = interpolateDouble(previous.implementLocalX, current.implementLocalX, progress),
                implementLocalY = interpolateDouble(previous.implementLocalY, current.implementLocalY, progress),
                jointLatLon = interpolateGeoPoint(previous.jointLatLon, current.jointLatLon, progress),
                implementLatLon = interpolateGeoPoint(previous.implementLatLon, current.implementLatLon, progress),
                axisX = normalizedAxisX,
                axisY = normalizedAxisY,
                thetaRad = interpolateAngle(previous.thetaRad, current.thetaRad, progress),
                hasMotion = current.hasMotion,
            )
        }

        private fun interpolateGeoPoint(prev: GeoPoint?, cur: GeoPoint?, progress: Double): GeoPoint? {
            prev ?: return cur
            cur ?: return prev
            val lat = prev.latitude + (cur.latitude - prev.latitude) * progress
            val lon = prev.longitude + (cur.longitude - prev.longitude) * progress
            return GeoPoint(lat, lon)
        }

        private fun interpolateDouble(prev: Double?, cur: Double?, progress: Double): Double? {
            prev ?: return cur
            cur ?: return prev
            return prev + (cur - prev) * progress
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
