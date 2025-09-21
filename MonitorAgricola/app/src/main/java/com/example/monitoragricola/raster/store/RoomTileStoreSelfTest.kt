package com.example.monitoragricola.raster.store

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import kotlinx.coroutines.runBlocking

/**
 * Pequeno "teste" executável sem depender de JUnit. Para rodar use o task
 * Gradle roomTileStoreSelfTest.
 */
object RoomTileStoreSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking { verifyPreloadWithoutPersistedTiles() }
    }

    suspend fun verifyPreloadWithoutPersistedTiles() {
        val engine = RasterCoverageEngine()
        engine.startJob(0.0, 0.0, resolutionM = 0.1, tileSize = 256)
        val store = RoomTileStore(FakeDb(), jobId = 1L)
        val missingKeys = listOf(TileKey(0, 0))

        store.preloadTiles(engine, missingKeys)

        check(!engine.isRestoringFromStore()) {
            "Esperado que restoringFromStore volte a false quando nenhum tile é persistido"
        }

        engine.attachStore(store)
        engine.updateTractorHotCenter(0.0, 0.0)

        check(engine.debugStats().hotCount > 0) {
            "Esperado que HOT volte a ser populado após updateTractorHotCenter"
        }
    }

    private class EmptyRasterTileDao : RasterTileDao {
        override suspend fun upsertTiles(tiles: List<RasterTileEntity>) {}
        override suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity? = null
        override suspend fun countByJob(jobId: Long): Int = 0
        override suspend fun listCoords(jobId: Long): List<RasterTileCoord> = emptyList()
        override suspend fun deleteByJob(jobId: Long) {}
    }

    private class NoopMetadataDao : JobRasterMetadataDao {
        override suspend fun upsert(entity: JobRasterMetadataEntity) {}
        override suspend fun select(jobId: Long): JobRasterMetadataEntity? = null
        override suspend fun deleteByJob(jobId: Long) {}
    }

    @Suppress("RestrictedApi")
    private class FakeDb : RasterDatabase() {
        private val tileDao = EmptyRasterTileDao()
        private val metadataDao = NoopMetadataDao()

        override fun rasterTileDao(): RasterTileDao = tileDao
        override fun jobRasterMetadataDao(): JobRasterMetadataDao = metadataDao

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
            throw UnsupportedOperationException("Not used in tests")
        }

        override fun createInvalidationTracker(): InvalidationTracker {
            throw UnsupportedOperationException("Not used in tests")
        }

        override fun clearAllTables() {}

        override fun getAutoMigrations(
            autoMigrationSpecs: Map<Class<out AutoMigrationSpec>, AutoMigrationSpec>
        ): List<Migration> = emptyList()

        override fun getRequiredAutoMigrationSpecs(): Set<Class<out AutoMigrationSpec>> = emptySet()

        override fun getRequiredTypeConverters(): Map<Class<*>, List<Class<*>>> = emptyMap()
    }

    private fun RasterCoverageEngine.isRestoringFromStore(): Boolean {
        val field = RasterCoverageEngine::class.java.getDeclaredField("restoringFromStore")
        field.isAccessible = true
        return field.get(this) as Boolean
    }
}