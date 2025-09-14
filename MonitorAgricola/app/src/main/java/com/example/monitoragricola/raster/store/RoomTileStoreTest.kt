/*package com.example.monitoragricola.raster.store

import android.util.LruCache
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.StoreTile


class RoomTileStoreTest {
    private class FakeDao : RasterTileDao {
        override suspend fun upsertTiles(tiles: List<RasterTileEntity>) {}
        override suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity? = null
        override suspend fun countByJob(jobId: Long): Int = 0
        override suspend fun deleteByJob(jobId: Long) {}
    }

    private class FakeDb : RasterDatabase() {
        private val dao = FakeDao()
        override fun rasterTileDao(): RasterTileDao = dao
    }

    @Test
    fun cacheIsLimitedToMaxTiles() {
        val store = RoomTileStore(FakeDb(), 1L)
        val cacheField = RoomTileStore::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(store) as LruCache<TileKey, StoreTile>
        for (i in 0 until 20) {
            val tile = StoreTile(i, i, 0, 0, ByteArray(0), null, null, null, ShortArray(0), null)
            cache.put(TileKey(i, i), tile)
        }
        store.logMem("Test")
        println("cacheSize=${cache.size()}")
        assertEquals(16, cache.size())
    }
}*/