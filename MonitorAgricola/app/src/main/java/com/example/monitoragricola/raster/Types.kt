// =============================================
// file: com/example/monitoragricola/raster/Types.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Color

/** Modo de renderização. */
enum class HotVizMode { COBERTURA, SOBREPOSICAO, TAXA, VELOCIDADE, SECOES }

/** Métricas de área. */
data class Areas(
    val totalM2: Double,
    val effectiveM2: Double,
    val overlapM2: Double,
    val bySectionM2: LongArray
)
/** Métricas de taxa. */
data class RateStats(
    val sumBySection: DoubleArray,
    val countBySection: LongArray,
    val meanBySection: DoubleArray,
    val sumByArea: Double,
    val countByArea: Long,
    val meanByArea: Double
)

/** Totais agregados persistíveis do raster. */
data class RasterTotals(
    val totalOncePx: Long,
    val totalOverlapPx: Long,
    val sectionPx: LongArray,
    val rateSumBySection: DoubleArray,
    val rateCountBySection: LongArray,
    val rateSumByArea: Double,
    val rateCountByArea: Long
)

/** Snapshot serializável do raster. */
data class RasterSnapshot(
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int,
    val tiles: List<SnapshotTile>
)

data class SnapshotTile(
    val tx: Int,
    val ty: Int,
    val rev: Int,
    val layerMask: Int,
    val count: ByteArray,
    val lastStrokeId: ShortArray,
    val sections: IntArray?,
    val rate: FloatArray?,
    val speed: FloatArray?,
    val frontStamp: ShortArray?
)

internal const val LAYER_COUNT = 1
internal const val LAYER_SECTIONS = 2
internal const val LAYER_RATE = 4
internal const val LAYER_SPEED = 8