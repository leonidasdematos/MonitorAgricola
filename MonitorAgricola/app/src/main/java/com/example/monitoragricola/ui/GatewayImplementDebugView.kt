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
        val direction = computeDirection(articulation.thetaRad, articulation.axisX, articulation.axisY)
        val antenna = articulation.antennaLocalX?.let { x ->
            articulation.antennaLocalY?.let { y -> Point(x, y) }
        } ?: Point(0.0, 0.0)

        val joint = articulation.jointLocalX?.let { x ->
            articulation.jointLocalY?.let { y -> Point(x, y) }
        } ?: articulation.antennaToJointMeters?.let { distance ->
            Point(antenna.x + direction.x * distance, antenna.y + direction.y * distance)
        }

        val implement = articulation.implementLocalX?.let { x ->
            articulation.implementLocalY?.let { y -> Point(x, y) }
        } ?: if (joint != null && articulation.jointToImplementMeters != null) {
            Point(
                joint.x + direction.x * articulation.jointToImplementMeters,
                joint.y + direction.y * articulation.jointToImplementMeters,
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
        val scale = computeScale(bounds, padding)
        val centerX = width / 2f
        val centerY = height / 2f

        fun Point.toScreen(): Pair<Float, Float> {
            val xOffset = ((x - bounds.centerX) * scale).toFloat()
            val yOffset = ((y - bounds.centerY) * scale).toFloat()
            val xPx = centerX + xOffset
            val yPx = centerY - yOffset
            return Pair(xPx, yPx)
        }

        // Axis
        val axisLength = max(width, height).toFloat() * 0.35f
        val axisEndX = centerX + (direction.x * axisLength).toFloat()
        val axisEndY = centerY - (direction.y * axisLength).toFloat()
        canvas.drawLine(centerX, centerY, axisEndX, axisEndY, axisPaint)

        // Connections
        val antennaScreen = antenna.toScreen()
        joint?.let { j ->
            val jointScreen = j.toScreen()
            canvas.drawLine(antennaScreen.first, antennaScreen.second, jointScreen.first, jointScreen.second, linePaint)
            implement?.let { impl ->
                val implScreen = impl.toScreen()
                canvas.drawLine(jointScreen.first, jointScreen.second, implScreen.first, implScreen.second, linePaint)
            }
        }

        // Points
        canvas.drawCircle(antennaScreen.first, antennaScreen.second, 12f, antennaPaint)
        canvas.drawText("Antena", antennaScreen.first + 16f, antennaScreen.second - 16f, textPaint)

        joint?.let { j ->
            val (x, y) = j.toScreen()
            canvas.drawCircle(x, y, 12f, jointPaint)
            canvas.drawText("Articulação", x + 16f, y - 16f, textPaint)
        }

        implement?.let { impl ->
            val (x, y) = impl.toScreen()
            canvas.drawCircle(x, y, 12f, implementPaint)
            canvas.drawText("Implemento", x + 16f, y - 16f, textPaint)
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

    private fun computeDirection(thetaRad: Double?, axisX: Double?, axisY: Double?): Point {
        val baseX = axisX ?: thetaRad?.let { kotlin.math.cos(it) } ?: 1.0
        val baseY = axisY ?: thetaRad?.let { kotlin.math.sin(it) } ?: 0.0
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
        // Avoid zero span to prevent division by zero when scaling
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