// =============================================
// file: com/example/monitoragricola/raster/RasterCoverageOverlay.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

/** Overlay que desenha somente VIZ (tiles que intersectam o viewport atual). */
class RasterCoverageOverlay(private val map: MapView, private val engine: RasterCoverageEngine) : Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()

    fun setHotVizMode(mode: HotVizMode) { engine.setMode(mode); map.postInvalidate() }
    fun invalidateTiles() { engine.invalidateTiles(); map.postInvalidate() }

    override fun draw(canvas: Canvas, projection: Projection) {
        val bb = map.boundingBox
        engine.updateViewport(bb)
        drawVisibleTiles(canvas, projection, bb)
    }

    private fun drawVisibleTiles(canvas: Canvas, projection: Projection, bb: BoundingBox) {
        val tileSize = engine.currentTileSize()
        val res = engine.currentResolutionM()
        val ph = engine.currentProjection() ?: return  // <-- use a mesma instância do engine
        // limites em “pixels do raster” a partir da projeção do ENGINE
        val p1 = ph.toLocalMeters(bb.latSouth, bb.lonWest)
        val p2 = ph.toLocalMeters(bb.latNorth, bb.lonEast)
        val minX = floor(min(p1.x,p2.x) / res).toInt()
        val maxX = ceil (max(p1.x,p2.x) / res).toInt()
        val minY = floor(min(p1.y,p2.y) / res).toInt()
        val maxY = ceil (max(p1.y,p2.y) / res).toInt()
        val tMinX = floor(minX.toDouble() / tileSize).toInt()
        val tMaxX = floor((maxX-1).toDouble() / tileSize).toInt()
        val tMinY = floor(minY.toDouble() / tileSize).toInt()
        val tMaxY = floor((maxY-1).toDouble() / tileSize).toInt()

        for (ty in tMinY..tMaxY) for (tx in tMinX..tMaxX) {
            val bmp = engine.buildOrGetBitmapFor(tx, ty) ?: continue

            val x0m = tx * tileSize * res
            val y0m = ty * tileSize * res
            val x1m = x0m + tileSize * res
            val y1m = y0m + tileSize * res

            // 4 cantos do tile no espaço geográfico usando a MESMA projeção do engine
            val gp00 = ph.toLatLon(org.locationtech.jts.geom.Coordinate(x0m, y0m))
            val gp10 = ph.toLatLon(org.locationtech.jts.geom.Coordinate(x1m, y0m))
            val gp11 = ph.toLatLon(org.locationtech.jts.geom.Coordinate(x1m, y1m))
            val gp01 = ph.toLatLon(org.locationtech.jts.geom.Coordinate(x0m, y1m))

            // para tela (respeita orientação do mapa)
            val p00 = android.graphics.Point(); val p10 = android.graphics.Point()
            val p11 = android.graphics.Point(); val p01 = android.graphics.Point()
            projection.toPixels(gp00, p00); projection.toPixels(gp10, p10)
            projection.toPixels(gp11, p11); projection.toPixels(gp01, p01)

            // mapeia (0,0)-(w,0)-(w,h)-(0,h) -> p00-p10-p11-p01
            val src = floatArrayOf(0f,0f, bmp.width.toFloat(),0f, bmp.width.toFloat(),bmp.height.toFloat(), 0f,bmp.height.toFloat())
            val dst = floatArrayOf(
                p00.x.toFloat(), p00.y.toFloat(),
                p10.x.toFloat(), p10.y.toFloat(),
                p11.x.toFloat(), p11.y.toFloat(),
                p01.x.toFloat(), p01.y.toFloat()
            )
            val mtx = android.graphics.Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
            canvas.drawBitmap(bmp, mtx, paint)
        }
    }

    // Conversão simples de metros locais → geográfico usando origem do job
    private fun localToGeo(lat0: Double, lon0: Double, xm: Double, ym: Double): GeoPoint {
        val metersPerDegLat = 111320.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat0))
        val lat = lat0 + (ym / metersPerDegLat)
        val lon = lon0 + (xm / metersPerDegLon)
        return GeoPoint(lat, lon)
    }
}
