package com.example.monitoragricola.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.monitoragricola.R
import com.example.monitoragricola.hardware.gateway.GatewayImplementTelemetry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class GatewayImplementDebugView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#607D8B")
        strokeWidth = 3f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 4f
    }
    private val antennaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E88E5")
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#43A047")
    }
    private val implementPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
    }

    private var telemetry: GatewayImplementTelemetry? = null
    private val reusablePoints = mutableListOf<Point>()

    fun updateTelemetry(telemetry: GatewayImplementTelemetry?) {
        this.telemetry = telemetry
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val t = telemetry
        if (t?.articulated != true || t.articulation == null) {
            drawEmptyState(canvas)
            return
        }

        val articulation = t.articulation

        // Antena em coordenadas locais
        val antenna = articulation.antennaLocalX?.let { x ->
            articulation.antennaLocalY?.let { y -> Point(x, y) }
        } ?: Point(0.0, 0.0)

        // Ponto de articulação
        val joint = articulation.jointLocalX?.let { x ->
            articulation.jointLocalY?.let { y -> Point(x, y) }
        } ?: articulation.antennaToJointMeters?.let { distance ->
            // fallback se só tiver distância
            val dir = computeDirection(
                thetaRad = articulation.thetaRad,
                axisX = articulation.axisX,
                axisY = articulation.axisY,
            )
            Point(
                antenna.x + dir.x * distance,
                antenna.y + dir.y * distance,
            )
        }

        // Centro do implemento
        val implement = articulation.implementLocalX?.let { x ->
            articulation.implementLocalY?.let { y -> Point(x, y) }
        } ?: if (joint != null && articulation.jointToImplementMeters != null) {
            val dir = computeDirection(
                thetaRad = articulation.thetaRad,
                axisX = articulation.axisX,
                axisY = articulation.axisY,
            )
            Point(
                joint.x + dir.x * articulation.jointToImplementMeters,
                joint.y + dir.y * articulation.jointToImplementMeters,
            )
        } else null

        reusablePoints.clear()
        reusablePoints.add(antenna)
        joint?.let { reusablePoints.add(it) }
        implement?.let { reusablePoints.add(it) }

        if (reusablePoints.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        val bounds = computeBounds(reusablePoints)
        val padding = 60f
        val scale = computeScale(bounds, padding) // px por metro aprox.
        val centerX = width / 2f
        val centerY = height / 2f

        fun Point.toScreen(): Pair<Float, Float> {
            val xOffset = ((x - bounds.centerX) * scale).toFloat()
            val yOffset = ((y - bounds.centerY) * scale).toFloat()
            val xPx = centerX + xOffset
            val yPx = centerY - yOffset
            return Pair(xPx, yPx)
        }

        // Desenha conexões: Antena -> Articulação -> Implemento
        val antennaScreen = antenna.toScreen()
        joint?.let { j ->
            val jointScreen = j.toScreen()
            canvas.drawLine(
                antennaScreen.first,
                antennaScreen.second,
                jointScreen.first,
                jointScreen.second,
                linePaint,
            )
            implement?.let { impl ->
                val implScreen = impl.toScreen()
                canvas.drawLine(
                    jointScreen.first,
                    jointScreen.second,
                    implScreen.first,
                    implScreen.second,
                    linePaint,
                )
            }
        }

        // Pontos
        canvas.drawCircle(antennaScreen.first, antennaScreen.second, 12f, antennaPaint)
        canvas.drawText(
            "Antena",
            antennaScreen.first + 16f,
            antennaScreen.second - 16f,
            textPaint,
        )

        joint?.let { j ->
            val (x, y) = j.toScreen()
            canvas.drawCircle(x, y, 12f, jointPaint)
            canvas.drawText("Articulação", x + 16f, y - 16f, textPaint)
        }

        implement?.let { impl ->
            val (ix, iy) = impl.toScreen()
            canvas.drawCircle(ix, iy, 12f, implementPaint)
            canvas.drawText("Implemento", ix + 16f, iy - 16f, textPaint)

            // === Barra de largura do implemento (T perfeito) ===

            if (joint != null) {
                // 1) Eixo da barra
                val axisDir = run {
                    // Tenta usar axisX/axisY enviados pelo gateway
                    val ax = articulation.axisX
                    val ay = articulation.axisY
                    if (ax != null && ay != null) {
                        val len = hypot(ax, ay).takeIf { it > 0.0 } ?: 1.0
                        Point(ax / len, ay / len)
                    } else {
                        // Fallback: perpendicular a (joint -> implement)
                        val vx = impl.x - joint.x
                        val vy = impl.y - joint.y
                        val vlen = hypot(vx, vy).takeIf { it > 0.0 } ?: 1.0
                        // perpendicular: (-y, x)
                        Point(-vy / vlen, vx / vlen)
                    }
                }

                // 2) Comprimento visual da barra (em metros, só para debug)
                val jointToImplMeters = hypot(
                    impl.x - joint.x,
                    impl.y - joint.y,
                )
                // Usa algo proporcional ao comprimento da haste pra ficar bonito
                val halfBarMeters = max(jointToImplMeters, 1.0)

                // Converte eixo (em metros) para deslocamento em pixels
                val dxPx = (axisDir.x * halfBarMeters * scale).toFloat()
                val dyPx = (-axisDir.y * halfBarMeters * scale).toFloat()

                val leftX = ix - dxPx
                val leftY = iy - dyPx
                val rightX = ix + dxPx
                val rightY = iy + dyPx

                // 3) Desenha a barra centrada no implemento,
                // perpendicular à linha articulação->implemento
                canvas.drawLine(leftX, leftY, rightX, rightY, axisPaint)
            }
        }
    }

    private fun computeScale(bounds: Bounds, padding: Float): Float {
        val availableWidth = width - padding * 2
        val availableHeight = height - padding * 2
        val spanX = if (bounds.width == 0.0) 1.0 else bounds.width
        val spanY = if (bounds.height == 0.0) 1.0 else bounds.height
        val scaleX = availableWidth / spanX
        val scaleY = availableHeight / spanY
        return min(scaleX, scaleY).toFloat()
    }

    /**
     * Retorna um vetor unitário de direção:
     * - Se axisX/axisY existirem, usa como direção base.
     * - Senão, usa thetaRad como fallback.
     */
    private fun computeDirection(thetaRad: Double?, axisX: Double?, axisY: Double?): Point {
        val baseX: Double
        val baseY: Double

        if (axisX != null && axisY != null) {
            baseX = axisX
            baseY = axisY
        } else if (thetaRad != null) {
            // Convenção do monitor: 0 rad = norte, gira horário.
            // Vetor "frente" em ENU: (sin, cos).
            baseX = kotlin.math.sin(thetaRad)
            baseY = kotlin.math.cos(thetaRad)
        } else {
            baseX = 1.0
            baseY = 0.0
        }

        val length = hypot(baseX, baseY).takeIf { it > 0 } ?: 1.0
        return Point(baseX / length, baseY / length)
    }

    private fun computeBounds(points: List<Point>): Bounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        points.forEach { p ->
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        if (minX.isInfinite() || minY.isInfinite() || maxX.isInfinite() || maxY.isInfinite()) {
            return Bounds(0.0, 0.0, 0.0, 0.0)
        }
        // Garante span mínimo pra não explodir escala
        if (abs(maxX - minX) < 0.01) {
            maxX += 0.5
            minX -= 0.5
        }
        if (abs(maxY - minY) < 0.01) {
            maxY += 0.5
            minY -= 0.5
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private fun drawEmptyState(canvas: Canvas) {
        val message = context.getString(R.string.gateway_debug_no_articulation)
        val textWidth = textPaint.measureText(message)
        canvas.drawText(message, (width - textWidth) / 2f, height / 2f, textPaint)
    }

    private data class Point(val x: Double, val y: Double)
    private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val centerX: Double get() = minX + width / 2.0
        val centerY: Double get() = minY + height / 2.0
    }
}
