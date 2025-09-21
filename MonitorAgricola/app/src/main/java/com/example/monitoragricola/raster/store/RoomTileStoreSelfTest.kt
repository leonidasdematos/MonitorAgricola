package com.example.monitoragricola.raster.store

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.monitoragricola.jobs.JobsRepository
import com.example.monitoragricola.jobs.JobState
import com.example.monitoragricola.jobs.db.JobDao
import com.example.monitoragricola.jobs.db.JobEntity
import com.example.monitoragricola.jobs.db.JobEventDao
import com.example.monitoragricola.jobs.db.JobEventEntity
import com.example.monitoragricola.jobs.db.JobPointDao
import com.example.monitoragricola.jobs.db.JobPointEntity
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow


/**
 * Pequeno "teste" executável sem depender de JUnit. Para rodar use o task
 * Gradle roomTileStoreSelfTest.
 */
object RoomTileStoreSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            verifyPreloadWithoutPersistedTiles()
            verifyCancelledPreloadWithoutPersistedTiles()
            verifyCancelledLoadRasterIntoDuringPreload()
        }
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

    suspend fun verifyCancelledPreloadWithoutPersistedTiles() {
        val engine = RasterCoverageEngine()
        engine.startJob(0.0, 0.0, resolutionM = 0.1, tileSize = 256)
        val store = RoomTileStore(FakeDb(SlowRasterTileDao(delayMs = 50L)), jobId = 2L)
        val missingKeys = listOf(TileKey(0, 0))

        coroutineScope {
            val job = launch {
                store.preloadTiles(engine, missingKeys)
            }
            yield()
            job.cancelAndJoin()
        }

        check(!engine.isRestoringFromStore()) {
            "Esperado que restoringFromStore volte a false após preload cancelado"
        }

        engine.attachStore(store)
        engine.updateTractorHotCenter(0.0, 0.0)

        check(engine.debugStats().hotCount > 0) {
            "Esperado que HOT volte a ser populado após preload cancelado"
        }
    }

    suspend fun verifyCancelledLoadRasterIntoDuringPreload() {
        val engine = RasterCoverageEngine()
        val jobId = 3L
        val coords = listOf(RasterTileCoord(0, 0))
        val metadata = JobRasterMetadataEntity(
            jobId = jobId,
            originLat = 0.0,
            originLon = 0.0,
            resolutionM = 0.1,
            tileSize = 256
        )
        val repository = JobsRepository(
            jobDao = NoopJobDao(),
            pointDao = NoopJobPointDao(),
            eventDao = NoopJobEventDao(),
            rasterDb = FakeDb(
                tileDao = SlowRasterTileDaoWithCoords(delayMs = 50L, coords = coords),
                metadataDao = SingleMetadataDao(metadata)
            )
        )

        coroutineScope {
            val job = launch { repository.loadRasterInto(jobId, engine) }
            yield()
            delay(10L)
            job.cancelAndJoin()
        }

        check(!engine.isRestoringFromStore()) {
            "Esperado que restoringFromStore volte a false após loadRasterInto cancelado"
        }

        check(engine.debugStats().hotCount == 0) {
            "Esperado que HOT esteja vazio antes do updateTractorHotCenter"
        }

        engine.updateTractorHotCenter(0.0, 0.0)

        check(engine.debugStats().hotCount > 0) {
            "Esperado que HOT volte a ser populado após loadRasterInto cancelado"
        }
    }


    private open class EmptyRasterTileDao : RasterTileDao {
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

    private class SingleMetadataDao(
        private val metadata: JobRasterMetadataEntity
    ) : JobRasterMetadataDao {
        override suspend fun upsert(entity: JobRasterMetadataEntity) {}
        override suspend fun select(jobId: Long): JobRasterMetadataEntity? =
            if (jobId == metadata.jobId) metadata else null
        override suspend fun deleteByJob(jobId: Long) {}
    }


    @Suppress("RestrictedApi")
    private class SlowRasterTileDao(private val delayMs: Long) : EmptyRasterTileDao() {
        override suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity? {
            delay(delayMs)
            return null
        }
    }

    @Suppress("RestrictedApi")
    private class SlowRasterTileDaoWithCoords(
        private val delayMs: Long,
        private val coords: List<RasterTileCoord>
    ) : EmptyRasterTileDao() {
        override suspend fun listCoords(jobId: Long): List<RasterTileCoord> = coords
        override suspend fun getTile(jobId: Long, tx: Int, ty: Int): RasterTileEntity? {
            delay(delayMs)
            return null
        }
    }


    private class FakeDb(
        private val tileDao: RasterTileDao = EmptyRasterTileDao(),
        private val metadataDao: JobRasterMetadataDao = NoopMetadataDao(),
        ) : RasterDatabase() {

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

        //override fun getRequiredAutoMigrationSpecs(): Set<Class<out AutoMigrationSpec>> = emptySet()

        //override fun getRequiredTypeConverters(): Map<Class<*>, List<Class<*>>> = emptyMap()
    }

    private class NoopJobDao : JobDao {
        override suspend fun insert(job: JobEntity): Long = 0L
        override suspend fun update(job: JobEntity) {}
        override suspend fun delete(jobId: Long) {}
        override fun observeAll(): Flow<List<JobEntity>> = emptyFlow()
        override fun observeByState(state: JobState): Flow<List<JobEntity>> = emptyFlow()
        override suspend fun get(jobId: Long): JobEntity? = null
        override suspend fun getActive(): JobEntity? = null
        override suspend fun deleteById(jobId: Long) {}
    }

    private class NoopJobPointDao : JobPointDao {
        override suspend fun insertAll(points: List<JobPointEntity>) {}
        override suspend fun insert(point: JobPointEntity): Long = 0L
        override suspend fun count(jobId: Long): Int = 0
        override suspend fun maxSeq(jobId: Long): Int? = null
        override suspend fun deleteByJob(jobId: Long) {}
        override suspend fun loadPointsPaged(jobId: Long, limit: Int, offset: Int): List<JobPointEntity> = emptyList()
        override suspend fun insertPointsBulk(points: List<JobPointEntity>) {}
        override suspend fun getPointsBetweenSeq(
            jobId: Long,
            startSeq: Int,
            endSeq: Int
        ): List<JobPointEntity> = emptyList()
    }

    private class NoopJobEventDao : JobEventDao {
        override suspend fun insert(event: JobEventEntity): Long = 0L
        override suspend fun deleteByJob(jobId: Long) {}
    }


    private fun RasterCoverageEngine.isRestoringFromStore(): Boolean {
        val field = RasterCoverageEngine::class.java.getDeclaredField("restoringFromStore")
        field.isAccessible = true
        return field.get(this) as Boolean
    }
}