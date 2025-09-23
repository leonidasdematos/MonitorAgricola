package com.example.monitoragricola.map

import android.util.Log
import com.example.monitoragricola.raster.RasterCoverageEngine
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
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

    private val EPS_STEP = 0.01
    private val EPS_IMPL = 0.01

    private var lastHeadingRad: Double? = null


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

    override fun updatePosition(last: GeoPoint?, current: GeoPoint) {
        if (last == null){ return }

        val proj = ProjectionHelper(current.latitude, current.longitude)
        val lastXY = proj.toLocalMeters(last)
        val curXY  = proj.toLocalMeters(current)

        val vx = curXY.x - lastXY.x
        val vy = curXY.y - lastXY.y
        val dist = hypot(vx, vy)

        // Atualize o heading SEMPRE que houver qualquer variação mensurável
        if (dist > 1e-9) {
            lastHeadingRad = atan2(vy, vx) // x=leste, y=norte (0° = norte)
        }

        // Defina fwd/right sem dar return precoce
        val (fwdX, fwdY) = if (dist >= EPS_STEP) {
            (vx / dist) to (vy / dist)
        } else {
            // Passo muito pequeno: use o heading cacheado; se não houver, assuma norte
            val th = lastHeadingRad ?: 0.0

            sin(th) to cos(th)
        }
        val rightX = fwdY
        val rightY = -fwdX

        // Centros do implemento conforme o modo (continua atualizando mesmo pausado)
        val (lastImplLocal, curImplLocal) = when (paintModel) {
            PaintModel.ARTICULADO ->
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
        if (running && !rasterSuspended && dImpl >= EPS_IMPL) {
            val lastImplLL = proj.toLatLon(lastImplLocal)
            val curImplLL2 = proj.toLatLon(curImplLocal)
            val lastImpl = GeoPoint(lastImplLL.latitude, lastImplLL.longitude)
            val curImpl  = GeoPoint(curImplLL2.latitude,  curImplLL2.longitude)

            try {

                rasterEngine.paintStroke(
                    last = lastImpl,
                    current = curImpl,
                    implementWidthMeters = w.toDouble(),
                    activeSectionsMask = 0,   // ou seu bitmask real
                    rateValue = null,
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

    open class RuntimeState(var thetaRad: Double? = null)
    open fun exportRuntimeState(): RuntimeState = RuntimeState(thetaRad = implThetaRad)
    open fun importRuntimeState(state: RuntimeState?) { implThetaRad = state?.thetaRad }
}
