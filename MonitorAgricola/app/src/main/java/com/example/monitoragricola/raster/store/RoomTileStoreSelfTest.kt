package com.example.monitoragricola.raster.store

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper

private class FakeTileDao : RasterTileDao {
    override suspend fun upsertTiles(tiles: List<RasterTileEntity>) = throw UnsupportedOperationException()
    override suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity? = throw UnsupportedOperationException()
    override suspend fun countByJob(jobId: Long): Int = throw UnsupportedOperationException()
    override suspend fun deleteByJob(jobId: Long) = throw UnsupportedOperationException()
}

private class FakeJobRasterMetadataDao : JobRasterMetadataDao {
    override suspend fun upsert(entity: JobRasterMetadataEntity) = throw UnsupportedOperationException()
    override suspend fun select(jobId: Long): JobRasterMetadataEntity? = throw UnsupportedOperationException()
    override suspend fun deleteByJob(jobId: Long) = throw UnsupportedOperationException()
}

class RoomTileStoreSelfTest {
    private class FakeDb : RasterDatabase() {
        private val tileDao = FakeTileDao()
        private val metadataDao = FakeJobRasterMetadataDao()

        override fun rasterTileDao(): RasterTileDao = tileDao

        override fun jobRasterMetadataDao(): JobRasterMetadataDao = metadataDao

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = throw UnsupportedOperationException()

        override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()

        override fun clearAllTables() = throw UnsupportedOperationException()
    }
}