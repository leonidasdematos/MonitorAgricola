package com.example.monitoragricola.jobs.coverage

import android.R.attr.entries
import com.example.monitoragricola.raster.RasterCoverageEngine
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.GeoPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Constrói um CoverageSnapshot a partir do RasterCoverageEngine.
 *
 * Observações:
 * - Nesta 1ª iteração, persistência mínima: NÃO serializamos os tiles; salvamos
 *   apenas métricas (área/overlap) e um bbox em GeoJSON (boundsGeoJson).
 * - coveredOnceWkb / coveredOverlapWkb ficam null (compatível com o schema atual).
 * - Futuro: adicionar JobRasterEntity (tiles compactados) sem quebrar API.
 */
class RasterCoverageProvider(
    private val engine: RasterCoverageEngine
) : CoverageSnapshotProvider {

    override fun buildSnapshot(): CoverageSnapshot {
        val areas = engine.getAreas()
        val bbox = boundsGeoJsonFromEngine(engine)
        return CoverageSnapshot(
            coveredOnceWkb = null,         // não salvamos vetorial nesta fase
            coveredOverlapWkb = null,      // idem
            areaM2 = areas.totalM2,
            overlapM2 = areas.overlapM2,
            boundsGeoJson = bbox
        )
    }

    /** BBox (Polygon) em GeoJSON da área coberta, derivado dos tiles tocados. */
    private fun boundsGeoJsonFromEngine(engine: RasterCoverageEngine): String? {
        val proj = engine.currentProjection() ?: return null
        val tiles = engine.tilesSnapshot()
        if (tiles.isEmpty()) return null

        val size = engine.currentTileSize()
        val res  = engine.currentResolutionM()

        // varre todas as chaves de tile e calcula os extremos em pixels locais
        val entries = engine.tilesSnapshot()

        var minTx = Int.MAX_VALUE
        var minTy = Int.MAX_VALUE
        var maxTx = Int.MIN_VALUE
        var maxTy = Int.MIN_VALUE

        for ((keyPacked, _) in entries) {
            val (tx, ty) = com.example.monitoragricola.raster.TileKey.unpack(keyPacked)
            if (tx < minTx) minTx = tx
            if (ty < minTy) minTy = ty
            if (tx > maxTx) maxTx = tx
            if (ty > maxTy) maxTy = ty
        }

        // bounds em pixels (inclusivo → exclusivo)
        val minPx = minTx * size
        val minPy = minTy * size
        val maxPx = (maxTx + 1) * size
        val maxPy = (maxTy + 1) * size

        // converte para metros locais
        val minX = minPx * res
        val minY = minPy * res
        val maxX = maxPx * res
        val maxY = maxPy * res

        // para lat/lon
        val ll = proj.toLatLon(Coordinate(minX, minY))
        val ur = proj.toLatLon(Coordinate(maxX, maxY))
        val west = min(ll.longitude, ur.longitude)
        val east = max(ll.longitude, ur.longitude)
        val south = min(ll.latitude, ur.latitude)
        val north = max(ll.latitude, ur.latitude)

        // GeoJSON Polygon (bbox)
        return """
        {
          "type":"Polygon",
          "coordinates":[[
            [$west,$south],
            [$east,$south],
            [$east,$north],
            [$west,$north],
            [$west,$south]
          ]]
        }
        """.trimIndent()
    }
}
