// com/example/monitoragricola/raster/store/TileStore.kt
package com.example.monitoragricola.raster.store

import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.TileKey

/** Pacote “cru” que o engine já entende (tileFromStore). */
data class StoreTile(
    val tx: Int,
    val ty: Int,
    val rev: Int,
    val count: ByteArray,
    val sections: IntArray?,      // opcional
    val rate: FloatArray?,        // opcional
    val speed: FloatArray?,       // opcional
    val lastStrokeId: ShortArray,
    val frontStamp: ShortArray?,  // opcional
    val layerMask: Int
)

/** Abstração de persistência por-tile. */
interface TileStore {
    /** Le o tile [tx, ty] ou null. */
    fun loadTile(tx: Int, ty: Int): StoreTile?

    /**
     * Salva um lote de tiles sujos. O engine garante coalescência por (tx,ty);
     * ao final, você deve setar tile.dirty=false (o engine também marca).
     */
    suspend fun saveDirtyTilesAndClear(batch: List<Pair<TileKey, TileData>>)
}
