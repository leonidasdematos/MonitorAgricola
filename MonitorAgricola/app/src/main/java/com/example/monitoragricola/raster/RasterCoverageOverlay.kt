// =============================================
// file: com/example/monitoragricola/raster/RasterCoverageOverlay.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

/** Overlay que desenha somente VIZ (tiles que intersectam o viewport atual). */
class RasterCoverageOverlay(private val map: MapView, private val engine: RasterCoverageEngine) : Overlay() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // objetos reutilizáveis para evitar garbage durante o draw
    private val matrix = Matrix()
    private val srcPts = FloatArray(8)
    private val dstPts = FloatArray(8)
    private val quadPath = Path()


    private val gp00 = GeoPoint(0.0, 0.0)
    private val gp10 = GeoPoint(0.0, 0.0)
    private val gp11 = GeoPoint(0.0, 0.0)
    private val gp01 = GeoPoint(0.0, 0.0)

    private val p00 = android.graphics.Point()
    private val p10 = android.graphics.Point()
    private val p11 = android.graphics.Point()
    private val p01 = android.graphics.Point()

    private val tmp1 = org.locationtech.jts.geom.Coordinate()
    private val tmp2 = org.locationtech.jts.geom.Coordinate()

    fun setHotVizMode(mode: HotVizMode) { engine.setMode(mode); map.postInvalidate() }
    fun invalidateTiles() { engine.invalidateTiles(); map.postInvalidate() }

    override fun draw(canvas: Canvas, projection: Projection) {
        val bb = map.boundingBox
        drawVisibleTiles(canvas, projection, bb)
    }

    private fun drawVisibleTiles(canvas: Canvas, projection: Projection, bb: BoundingBox) {
        val overview = engine.currentOverviewState()
        if (overview != null) {
            drawOverview(canvas, projection, overview)
            return
        }

        val tileSize = engine.currentTileSize()
        val res = engine.currentResolutionM()
        engine.currentProjection() ?: return

        val lat0 = engine.currentOriginLat()
        val lon0 = engine.currentOriginLon()
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0))

        geoToLocal(lat0, lon0, mPerDegLat, mPerDegLon, bb.latSouth, bb.lonWest, tmp1)
        geoToLocal(lat0, lon0, mPerDegLat, mPerDegLon, bb.latNorth, bb.lonEast, tmp2)
        val minX = floor(min(tmp1.x, tmp2.x) / res).toInt()
        val maxX = ceil(max(tmp1.x, tmp2.x) / res).toInt()
        val minY = floor(min(tmp1.y, tmp2.y) / res).toInt()
        val maxY = ceil(max(tmp1.y, tmp2.y) / res).toInt()

        val tMinX = floor(minX.toDouble() / tileSize).toInt()
        val tMaxX = floor((maxX - 1).toDouble() / tileSize).toInt()
        val tMinY = floor(minY.toDouble() / tileSize).toInt()
        val tMaxY = floor((maxY - 1).toDouble() / tileSize).toInt()

        for (ty in tMinY..tMaxY) for (tx in tMinX..tMaxX) {
            val x0m = tx * tileSize * res
            val y0m = ty * tileSize * res
            val x1m = x0m + tileSize * res
            val y1m = y0m + tileSize * res

            localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x0m, y0m, gp00)
            localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x1m, y0m, gp10)
            localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x1m, y1m, gp11)
            localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x0m, y1m, gp01)


            projection.toPixels(gp00, p00); projection.toPixels(gp10, p10)
            projection.toPixels(gp11, p11); projection.toPixels(gp01, p01)

            val widthPx = hypot((p10.x - p00.x).toDouble(), (p10.y - p00.y).toDouble())
            val heightPx = hypot((p01.x - p00.x).toDouble(), (p01.y - p00.y).toDouble())
            val stride = computeStride(tileSize, widthPx, heightPx)
            val bmp = engine.buildOrGetBitmapFor(tx, ty, stride) ?: continue

            srcPts[0] = 0f; srcPts[1] = 0f
            srcPts[2] = bmp.width.toFloat(); srcPts[3] = 0f
            srcPts[4] = bmp.width.toFloat(); srcPts[5] = bmp.height.toFloat()
            srcPts[6] = 0f; srcPts[7] = bmp.height.toFloat()

            dstPts[0] = p00.x.toFloat(); dstPts[1] = p00.y.toFloat()
            dstPts[2] = p10.x.toFloat(); dstPts[3] = p10.y.toFloat()
            dstPts[4] = p11.x.toFloat(); dstPts[5] = p11.y.toFloat()
            dstPts[6] = p01.x.toFloat(); dstPts[7] = p01.y.toFloat()

            matrix.reset()
            matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }

    private fun localToGeo(
        lat0: Double,
        lon0: Double,
        mPerDegLat: Double,
        mPerDegLon: Double,
        xm: Double,
        ym: Double,
        out: GeoPoint
    ): GeoPoint {
        out.latitude = lat0 + (ym / mPerDegLat)
        out.longitude = lon0 + (xm / mPerDegLon)
        return out
    }

    private fun geoToLocal(
        lat0: Double,
        lon0: Double,
        mPerDegLat: Double,
        mPerDegLon: Double,
        lat: Double,
        lon: Double,
        out: org.locationtech.jts.geom.Coordinate
    ): org.locationtech.jts.geom.Coordinate {
        out.x = (lon - lon0) * mPerDegLon
        out.y = (lat - lat0) * mPerDegLat
        return out
    }


    private fun computeStride(tileSize: Int, widthPx: Double, heightPx: Double): Int {
        val maxDim = max(widthPx, heightPx).coerceAtLeast(1.0)
        val ratio = tileSize.toDouble() / maxDim
        var stride = 1
        while (stride < tileSize && ratio >= (stride * 2).toDouble()) {
            stride *= 2
        }
        return stride
    }

    private fun drawOverview(
        canvas: Canvas,
        projection: Projection,
        overview: RasterCoverageEngine.OverviewState
    ) {
        val tileSize = engine.currentTileSize()
        val res = engine.currentResolutionM()
        engine.currentProjection() ?: return

        val lat0 = engine.currentOriginLat()
        val lon0 = engine.currentOriginLon()
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat0))
        val strideTiles = overview.strideTiles
        val bitmap = overview.bitmap
        val tileWidth = overview.tileWidth
        val tileHeight = overview.tileHeight

        paint.style = Paint.Style.FILL

        for (row in 0 until bitmap.height) {
            val tilesY = min(strideTiles, tileHeight - row * strideTiles).coerceAtLeast(0)
            if (tilesY <= 0) continue
            val baseTy = overview.tileMinY + row * strideTiles
            val y0m = baseTy * tileSize * res
            val y1m = (baseTy + tilesY) * tileSize * res

            for (col in 0 until bitmap.width) {
                val color = bitmap.getPixel(col, row)
                if (color == 0) continue
                val tilesX = min(strideTiles, tileWidth - col * strideTiles).coerceAtLeast(0)
                if (tilesX <= 0) continue
                val baseTx = overview.tileMinX + col * strideTiles
                val x0m = baseTx * tileSize * res
                val x1m = (baseTx + tilesX) * tileSize * res

                localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x0m, y0m, gp00)
                localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x1m, y0m, gp10)
                localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x1m, y1m, gp11)
                localToGeo(lat0, lon0, mPerDegLat, mPerDegLon, x0m, y1m, gp01)

                projection.toPixels(gp00, p00)
                projection.toPixels(gp10, p10)
                projection.toPixels(gp11, p11)
                projection.toPixels(gp01, p01)

                quadPath.reset()
                quadPath.moveTo(p00.x.toFloat(), p00.y.toFloat())
                quadPath.lineTo(p10.x.toFloat(), p10.y.toFloat())
                quadPath.lineTo(p11.x.toFloat(), p11.y.toFloat())
                quadPath.lineTo(p01.x.toFloat(), p01.y.toFloat())
                quadPath.close()

                paint.color = color
                canvas.drawPath(quadPath, paint)
            }
        }
    }
}
