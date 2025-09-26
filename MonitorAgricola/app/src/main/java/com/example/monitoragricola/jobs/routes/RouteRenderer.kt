// com/example/monitoragricola/jobs/routes/RouteRenderer.kt
package com.example.monitoragricola.jobs.routes

import android.graphics.Color
import com.example.monitoragricola.jobs.routes.db.JobRouteEntity
import com.example.monitoragricola.map.ProjectionHelper
import org.locationtech.jts.geom.*
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurveBuilder
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

data class GuidanceState( // NEW
    val laneIdx: Int,
    val lateralErrorM: Double,     // com sinal (+ direita, - esquerda do sentido da faixa)
    val headingTargetDeg: Double   // 0..360, direção da faixa no ponto projetado
)

class RouteRenderer(
    private val map: MapView
) {
    private val gf = GeometryFactory()
    private val wkbReader = WKBReader()

    private var polylines: List<Polyline> = emptyList()
    //private var crossLine: Polyline? = null         // NEW: linha do erro
    //private var headingLine: Polyline? = null       // NEW: seta do heading (na faixa)

    private var lastIdx: Int? = null
    private var visible: Boolean = true


    /** Atualiza as 3 linhas e elementos visuais e retorna GuidanceState. */
    fun update(
        route: JobRouteEntity,
        refLineWkb: ByteArray?,   // para Curva, idx=0
        tractorPos: GeoPoint,
        spacingM: Double
    ): GuidanceState? {
        if (!visible) return null
        return when (route.type) {
            RouteType.AB_STRAIGHT -> updateAB(route, tractorPos, spacingM)
            RouteType.AB_CURVE    -> updateCurve(route, refLineWkb, tractorPos, spacingM)
        }
    }

    private fun updateAB(
        route: JobRouteEntity,
        tractorPos: GeoPoint,
        spacingM: Double
    ): GuidanceState? {
        val proj = ProjectionHelper(route.originLat, route.originLon)

        val A = proj.toLocalMeters(GeoPoint(route.aLat!!, route.aLon!!))
        val B = proj.toLocalMeters(GeoPoint(route.bLat!!, route.bLon!!))
        val dx = B.x - A.x
        val dy = B.y - A.y
        val L = hypot(dx, dy).coerceAtLeast(1.0)
        val ux = dx / L
        val uy = dy / L
        val nx = -uy
        val ny =  ux

        val T = proj.toLocalMeters(tractorPos)
        // projeção do trator na AB
        val relx = T.x - A.x
        val rely = T.y - A.y
        val along = relx * ux + rely * uy
        val px = A.x + along * ux
        val py = A.y + along * uy

        val signedOffset = relx * nx + rely * ny
        val kCenter = (signedOffset / spacingM).roundToInt()

        // redesenha 3 linhas só quando troca de índice
        if (lastIdx != kCenter) {
            lastIdx = kCenter
            val half = 2000.0
            val midX = (A.x + B.x) / 2.0
            val midY = (A.y + B.y) / 2.0
            fun build(offset: Double): LineString {
                val ox = midX + nx * offset
                val oy = midY + ny * offset
                val p1 = Coordinate(ox - ux * half, oy - uy * half)
                val p2 = Coordinate(ox + ux * half, oy + uy * half)
                return gf.createLineString(arrayOf(p1, p2))
            }
            val lines = listOf(kCenter - 1, kCenter, kCenter + 1).map { k -> build(k * spacingM) }
            drawPolylines(proj, lines, activeIndex = 1)
        }

        // desenha cross-track (T -> P) e seta de heading na faixa
        /*drawCrossAndHeading(
            proj = proj,
            tractorXY = Coordinate(T.x, T.y),
            projXY = Coordinate(px, py),
            tangent = Pair(ux, uy)
        )*/

        val headingDeg = Math.toDegrees(atan2(uy, ux)).let { if (it < 0) it + 360 else it.toDouble() }
        return GuidanceState(
            laneIdx = kCenter,
            lateralErrorM = signedOffset,
            headingTargetDeg = headingDeg
        )
    }

    private fun updateCurve(
        route: JobRouteEntity,
        refLineWkb: ByteArray?,
        tractorPos: GeoPoint,
        spacingM: Double
    ): GuidanceState? {
        if (refLineWkb == null) return null
        val proj = ProjectionHelper(route.originLat, route.originLon)
        val T = proj.toLocalMeters(tractorPos)
        val ref = wkbReader.read(refLineWkb) as LineString

        val (cp, segIdx) = nearestOnLine(ref, Coordinate(T.x, T.y))
        val (tx, ty) = segmentTangent(ref, segIdx)

        // sinal pelo produto vetorial: + direita, - esquerda (convenção)
        val vx = T.x - cp.x
        val vy = T.y - cp.y
        val sign = sign(tx, ty, vx, vy)
        val signedOffset = sign * hypot(vx, vy)
        val kCenter = (signedOffset / spacingM).roundToInt()

        if (lastIdx != kCenter) {
            lastIdx = kCenter
            val params = BufferParameters().apply {
                endCapStyle = BufferParameters.CAP_ROUND
                joinStyle = BufferParameters.JOIN_ROUND
                quadrantSegments = 8
            }
            val ocb = OffsetCurveBuilder(gf.precisionModel, params)
            val ks = listOf(kCenter - 1, kCenter, kCenter + 1)
            val lines = ks.mapNotNull { k ->
                val d = k * spacingM
                val coords = ocb.getOffsetCurve(ref.coordinates, d) ?: return@mapNotNull null
                if (coords.size < 2) return@mapNotNull null
                gf.createLineString(coords)
            }
            drawPolylines(proj, lines, activeIndex = 1)
        }

        /*drawCrossAndHeading(
            proj = proj,
            tractorXY = Coordinate(T.x, T.y),
            projXY = cp,
            tangent = Pair(tx, ty)
        )*/

        val headingDeg = Math.toDegrees(atan2(ty, tx)).let { if (it < 0) it + 360 else it.toDouble() }
        return GuidanceState(
            laneIdx = kCenter,
            lateralErrorM = signedOffset,
            headingTargetDeg = headingDeg
        )
    }

    private fun drawPolylines(proj: ProjectionHelper, lines: List<LineString>, activeIndex: Int) {
        if (!visible) return
        // remove antigas
        polylines.forEach { map.overlays.remove(it) }

        val newPolys = lines.mapIndexed { i, ls ->
            val pts = (0 until ls.numPoints).map { n ->
                val c = ls.getCoordinateN(n)
                val ll = proj.toLatLon(Coordinate(c.x, c.y))
                GeoPoint(ll.latitude, ll.longitude)
            }
            Polyline(map).apply {
                setPoints(pts)
                outlinePaint.strokeWidth = if (i == activeIndex) 6f else 3f
                outlinePaint.color = if (i == activeIndex)
                    Color.argb(230, 0, 180, 255)   // ativa
                else
                    Color.argb(170, 120, 120, 120) // vizinhas
                isGeodesic = false
            }.also { map.overlays.add(it) }
        }
        polylines = newPolys
        map.invalidate()
    }

    // NEW: desenha linha de erro + seta do heading da faixa
    /*private fun drawCrossAndHeading(
        proj: ProjectionHelper,
        tractorXY: Coordinate,
        projXY: Coordinate,
        tangent: Pair<Double, Double>
    ) {
        // Cross-track: segmento T -> P
        val tLL = proj.toLatLon(tractorXY)
        val pLL = proj.toLatLon(projXY)
        val crossPts = listOf(
            GeoPoint(tLL.latitude, tLL.longitude),
            GeoPoint(pLL.latitude, pLL.longitude)
        )
        if (crossLine == null) {
            crossLine = Polyline(map).apply {
                outlinePaint.strokeWidth = 5f
                outlinePaint.color = Color.argb(230, 255, 60, 60) // vermelho
                setPoints(crossPts)
                isGeodesic = false
            }
            map.overlays.add(crossLine)
        } else {
            crossLine?.setPoints(crossPts)
        }

        // Heading: pequena seta 12 m ao longo da faixa partindo de P
        val len = 12.0
        val (ux, uy) = tangent.normalize()
        val hx = projXY.x + ux * len
        val hy = projXY.y + uy * len
        val hLL1 = proj.toLatLon(projXY)
        val hLL2 = proj.toLatLon(Coordinate(hx, hy))
        val headPts = listOf(
            GeoPoint(hLL1.latitude, hLL1.longitude),
            GeoPoint(hLL2.latitude, hLL2.longitude)
        )
        if (headingLine == null) {
            headingLine = Polyline(map).apply {
                outlinePaint.strokeWidth = 5f
                outlinePaint.color = Color.argb(230, 60, 200, 90) // verde
                setPoints(headPts)
                isGeodesic = false
            }
            map.overlays.add(headingLine)
        } else {
            headingLine?.setPoints(headPts)
        }

        map.invalidate()
    }*/

    private fun Pair<Double, Double>.normalize(): Pair<Double, Double> {
        val n = hypot(first, second).coerceAtLeast(1e-9)
        return (first / n) to (second / n)
    }

    private fun nearestOnLine(ls: LineString, p: Coordinate): Pair<Coordinate, Int> {
        var bestDist = Double.MAX_VALUE
        var bestPoint = ls.getCoordinateN(0)
        var bestSeg = 0
        for (i in 0 until ls.numPoints - 1) {
            val a = ls.getCoordinateN(i)
            val b = ls.getCoordinateN(i + 1)
            val proj = projectPointOnSegment(a, b, p)
            val d = proj.distance(p)
            if (d < bestDist) {
                bestDist = d
                bestPoint = proj
                bestSeg = i
            }
        }
        return bestPoint to bestSeg
    }

    private fun segmentTangent(ls: LineString, segIdx: Int): Pair<Double, Double> {
        val a = ls.getCoordinateN(max(0, min(segIdx, ls.numPoints - 2)))
        val b = ls.getCoordinateN(min(ls.numPoints - 1, segIdx + 1))
        val dx = b.x - a.x
        val dy = b.y - a.y
        val L = hypot(dx, dy).coerceAtLeast(1e-6)
        return (dx / L) to (dy / L)
    }

    private fun projectPointOnSegment(a: Coordinate, b: Coordinate, p: Coordinate): Coordinate {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val ab2 = abx * abx + aby * aby
        if (ab2 <= 1e-9) return Coordinate(a.x, a.y)
        val apx = p.x - a.x
        val apy = p.y - a.y
        val t = (apx * abx + apy * aby) / ab2
        val tClamped = max(0.0, min(1.0, t))
        return Coordinate(a.x + abx * tClamped, a.y + aby * tClamped)
    }

    private fun sign(tx: Double, ty: Double, vx: Double, vy: Double): Double {
        val cross = tx * vy - ty * vx
        return when {
            cross > 0 -> +1.0
            cross < 0 -> -1.0
            else -> 0.0
        }
    }

    fun reset() {
        lastIdx = null
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!visible) {
            polylines.forEach { map.overlays.remove(it) }
            polylines = emptyList()
            map.invalidate()
        } else {
            lastIdx = null
        }
    }



}
