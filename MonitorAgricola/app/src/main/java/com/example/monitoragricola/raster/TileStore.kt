// =============================================
// file: com/example/monitoragricola/raster/TileStore.kt
// =============================================
package com.example.monitoragricola.raster

/** Representação compacta de um tile no storage. */
data class StoreTile(
    val tx: Int,
    val ty: Int,
    val rev: Int,
    val layerMask: Int,
    val count: ByteArray,
    val sections: IntArray?,
    val rate: FloatArray?,
    val speed: FloatArray?,
    val lastStrokeId: ShortArray,
    val frontStamp: ShortArray?
)

interface TileStore {
    /** Deve ser rápido (cache interno), mas o acesso pesado roda em Dispatchers.IO no chamador. */
    fun loadTile(tx: Int, ty: Int): StoreTile?
    suspend fun saveDirtyTilesAndClear(list: List<Pair<TileKey, TileData>>)

    /** Opcional: persistir snapshot completo do raster */
    fun snapshot(meta: RasterSnapshot) {}

    /** Opcional: restaurar snapshot persistido */
    fun restore(): RasterSnapshot? = null

    /** Opcional: limpar todos os tiles persistidos */
    fun clear() {}

}
