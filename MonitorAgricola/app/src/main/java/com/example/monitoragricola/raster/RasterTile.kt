package com.example.monitoragricola.raster

/**
 * Um tile de tamanho fixo (size×size) contendo as 3 camadas.
 * - count: UByteArray
 * - sections: IntArray
 * - rate: FloatArray? (lazy)
 */
class RasterTile(
    val size: Int,
    private val enableRate: Boolean
) {
    val count: UByteArray = UByteArray(size * size) { 0u }
    val sections: IntArray = IntArray(size * size) { 0 }
    var rate: FloatArray? = if (enableRate) FloatArray(size * size) { Float.NaN } else null
        private set

    @Volatile var dirty: Boolean = true // quando true, o overlay recompõe o bitmap

    fun ensureRate() {
        if (rate == null && enableRate) {
            rate = FloatArray(size * size) { Float.NaN }
        }
    }
}
