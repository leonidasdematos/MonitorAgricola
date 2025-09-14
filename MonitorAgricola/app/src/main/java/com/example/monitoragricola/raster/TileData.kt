// =============================================
// file: com/example/monitoragricola/raster/TileData.kt
// =============================================
package com.example.monitoragricola.raster

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Estrutura por tile (tamanho fixo tileSize x tileSize).
 * Arrays lazy são alocados somente quando escritos.
 */
class TileData(
    val tileSize: Int,
    count: ByteArray? = null,
    sections: IntArray? = null,
    rate: FloatArray? = null,
    speed: FloatArray? = null,
    lastStrokeId: ShortArray? = null,
    frontStamp: ShortArray? = null,
    layerMask: Int = 0,
) {
    @Volatile var dirty: Boolean = false
    @Volatile var rev: Int = 0 // invalidação de bitmap
    @Volatile var layerMask: Int = layerMask

    val count: ByteArray = count ?: ByteArray(tileSize * tileSize) // 0..255
    var sections: IntArray? = sections
    var rate: FloatArray? = rate
    var speed: FloatArray? = speed
    var lastStrokeId: ShortArray? = lastStrokeId // ping-pong por stroke
    var frontStamp: ShortArray? = frontStamp

    fun ensureSections() { if (sections == null) { sections = IntArray(tileSize * tileSize); layerMask = layerMask or LAYER_SECTIONS } }
    fun ensureRate()     { if (rate == null)     { rate = FloatArray(tileSize * tileSize); layerMask = layerMask or LAYER_RATE } }
    fun ensureSpeed()    { if (speed == null)    { speed = FloatArray(tileSize * tileSize); layerMask = layerMask or LAYER_SPEED } }
    fun ensureFront()    { if (frontStamp == null){ frontStamp = ShortArray(tileSize * tileSize) } }
    fun ensureLastStrokeId() { if (lastStrokeId == null){ lastStrokeId = ShortArray(tileSize * tileSize) } }

}

data class TileKey(val tx: Int, val ty: Int) {
    fun pack(): Long = (tx.toLong() shl 32) xor (ty.toLong() and 0xffffffffL)
    companion object { fun unpack(k: Long) = TileKey((k shr 32).toInt(), k.toInt()) }
}
