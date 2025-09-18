// =============================================
// file: com/example/monitoragricola/raster/LruCaches.kt
// =============================================
package com.example.monitoragricola.raster

import android.graphics.Bitmap
import java.util.HashMap
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
        private val recyclePool = HashMap<Long, ArrayDeque<Bitmap>>()
        private var recycleCount = 0

        private fun poolKey(width: Int, height: Int): Long =
            (width.toLong() shl 32) xor (height.toLong() and 0xffffffffL)
        @Synchronized
        fun obtain(width: Int, height: Int): Pair<Bitmap, Boolean> {
            val key = poolKey(width, height)
            val deque = recyclePool[key]
            if (deque != null) {
                while (deque.isNotEmpty()) {
                    val bmp = deque.removeFirst()
                    recycleCount--
                    if (bmp.isRecycled) continue
                    if (bmp.width == width && bmp.height == height) {
                        return bmp to true
                    }
                }
                if (deque.isEmpty()) {
                    recyclePool.remove(key)
                }
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) to false

        }

        @Synchronized
        private fun recycle(bmp: Bitmap) {
            if (bmp.isRecycled) return
            if (recycleCount >= RECYCLE_POOL_MAX) {
                bmp.recycle()
                return
            }
            val key = poolKey(bmp.width, bmp.height)
            val deque = recyclePool.getOrPut(key) { ArrayDeque() }
            deque.addLast(bmp)
            recycleCount++
        }
    }

    private data class BitmapCacheKey(val tileKey: Long, val stride: Int)
    private data class CacheEntry(val rev: Int, val bitmap: Bitmap)


    private val map = object : LinkedHashMap<BitmapCacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BitmapCacheKey, CacheEntry>?): Boolean {
            val evict = size > maxEntries
            if (evict && eldest != null) recycle(eldest.value.bitmap)
            return evict
        }
    }

    @Synchronized
    fun get(tileKey: Long, stride: Int, expectedRev: Int): Bitmap? {
        val key = BitmapCacheKey(tileKey, stride)
        val entry = map[key] ?: return null
        val bmp = entry.bitmap
        return if (entry.rev == expectedRev && !bmp.isRecycled) bmp else null
    }

    @Synchronized
    fun put(tileKey: Long, stride: Int, rev: Int, bmp: Bitmap) {
        map[BitmapCacheKey(tileKey, stride)] = CacheEntry(rev, bmp)
    }

    @Synchronized
    fun invalidate(tileKey: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.tileKey == tileKey) {
                recycle(entry.value.bitmap)
                it.remove()
            }
        }
    }

    @Synchronized
    fun invalidateAll() {
        map.values.forEach { recycle(it.bitmap) }
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}