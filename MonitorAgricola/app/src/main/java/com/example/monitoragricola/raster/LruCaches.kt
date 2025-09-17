// =============================================
// file: com/example/monitoragricola/raster/LruCaches.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Bitmap
import android.util.Log
import java.util.ArrayDeque
import java.util.LinkedHashMap

/** LRU genérico de dados (TileData) baseado em LinkedHashMap. */
internal class TileDataLRU(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<Long, TileData>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TileData>?): Boolean {
            return size > maxEntries
        }
    }
    @Synchronized fun get(key: Long): TileData? = map[key]
    @Synchronized fun put(key: Long, value: TileData) { map[key] = value }
    @Synchronized fun remove(key: Long): TileData? = map.remove(key)
    @Synchronized fun entries(): List<Pair<Long, TileData>> = map.entries.map { it.key to it.value }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun keys(): Set<Long> = map.keys
    @Synchronized fun clear() = map.clear()
}

/** LRU de bitmaps com pool de reciclagem. */
internal class BitmapLRU(private val maxEntries: Int) {
    companion object {

        private const val RECYCLE_POOL_MAX = 64
        private val recyclePool = ArrayDeque<Bitmap>()

        @Synchronized
        fun obtain(width: Int, height: Int): Pair<Bitmap, Boolean> {
            val bmp = recyclePool.pollFirst()
            Log.d("BitmapLRU", "recycle pool size: ${recyclePool.size}")

            return if (bmp != null) {
                bmp to true
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) to false
            }
        }

        @Synchronized
        private fun recycle(bmp: Bitmap) {
            if (recyclePool.size >= RECYCLE_POOL_MAX) {
                bmp.recycle()
            } else {
                recyclePool.addLast(bmp)
            }        }
    }


    private val map = object : LinkedHashMap<Long, Pair<Int, Bitmap>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<Int, Bitmap>>?): Boolean {
            val evict = size > maxEntries
            if (evict && eldest != null) recycle(eldest.value.second)
            return evict
        }
    }
    @Synchronized fun get(key: Long, expectedRev: Int): Bitmap? = map[key]?.let { (rev, bmp) -> if (rev == expectedRev && !bmp.isRecycled) bmp else null }
    @Synchronized fun put(key: Long, rev: Int, bmp: Bitmap) { map[key] = rev to bmp }
    @Synchronized fun invalidate(key: Long) { map.remove(key)?.second?.let { recycle(it) } }
    @Synchronized fun invalidateAll() { map.values.forEach { recycle(it.second) }; map.clear() }
    @Synchronized fun size(): Int = map.size
}