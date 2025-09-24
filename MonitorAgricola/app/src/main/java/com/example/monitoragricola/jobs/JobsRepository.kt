package com.example.monitoragricola.jobs

import android.util.Log
import com.example.monitoragricola.jobs.db.*
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.store.JobRasterMetadata
import com.example.monitoragricola.raster.store.RasterDatabase
import com.example.monitoragricola.raster.store.RasterTileCoord
import com.example.monitoragricola.raster.store.toDomain
import com.example.monitoragricola.raster.store.toEntity
import com.example.monitoragricola.raster.RasterTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong


class JobsRepository(
    private val jobDao: JobDao,
    private val pointDao: JobPointDao,
    private val eventDao: JobEventDao,
    private val rasterDb: RasterDatabase
) {

    private val rasterTileDao = rasterDb.rasterTileDao()
    private val rasterMetadataDao = rasterDb.jobRasterMetadataDao()



    fun observeAll(): Flow<List<JobEntity>> = jobDao.observeAll()

    suspend fun getActive(): JobEntity? = jobDao.getActive()
    suspend fun get(jobId: Long): JobEntity? = jobDao.get(jobId)
    suspend fun insert(job: JobEntity): Long = jobDao.insert(job)
    suspend fun update(job: JobEntity) = jobDao.update(job)
    suspend fun insertPoint(p: JobPointEntity) = pointDao.insert(p)
    suspend fun nextSeq(jobId: Long): Int = (pointDao.maxSeq(jobId) ?: 0) + 1
    suspend fun addEvent(e: JobEventEntity) = eventDao.insert(e)
    // jobs/JobsRepository.kt
    suspend fun deleteJobCascade(jobId: Long) = withContext(Dispatchers.IO) {
        // apaga filhos antes
        pointDao.deleteByJob(jobId)
        eventDao.deleteByJob(jobId)
        rasterTileDao.deleteByJob(jobId)
        rasterMetadataDao.deleteByJob(jobId)
        // se tiver rotas/tabelas relacionadas, inclua aqui:
        // routeDao.deleteByJob(jobId)
        // routeLineDao.deleteByJob(jobId)

        jobDao.deleteById(jobId)
    }


    suspend fun loadPointsPaged(jobId: Long, limit: Int, offset: Int) =
        pointDao.loadPointsPaged(jobId, limit, offset)
    suspend fun insertPointsBulk(points: List<JobPointEntity>) =
        pointDao.insertPointsBulk(points)

    suspend fun getPointsBetweenSeq(jobId: Long, startSeq: Int, endSeq: Int) =
        pointDao.getPointsBetweenSeq(jobId, startSeq, endSeq)
    suspend fun maxSeq(jobId: Long): Int = pointDao.maxSeq(jobId) ?: 0

    suspend fun loadRasterInto(jobId: Long, engine: RasterCoverageEngine): Boolean =
        withContext(Dispatchers.IO) {
            val store = com.example.monitoragricola.raster.store.RoomTileStore(rasterDb, jobId)
            val coords = rasterTileDao.listCoords(jobId)
            val tileKeys = coords.map { TileKey(it.tx, it.ty) }
            val persistedMetadata = rasterMetadataDao.select(jobId)?.toDomain()
            val effectiveMetadata = persistedMetadata ?: deriveFallbackRasterMetadata(jobId, engine, coords)

            withContext(Dispatchers.Default) {
                engine.startJob(
                    effectiveMetadata.originLat,
                    effectiveMetadata.originLon,
                    effectiveMetadata.resolutionM,
                    effectiveMetadata.tileSize
                )
                val totals = persistedMetadata?.totals
                if (totals != null) {
                    engine.restorePersistedTotals(totals, tileKeys)
                }
            }

            if (persistedMetadata?.totals == null && tileKeys.isNotEmpty()) {
                runCatching { store.preloadTiles(engine, tileKeys) }
                    .onFailure { Log.w("JobsRepository", "Falha ao hidratar raster legacy", it) }
            }

            engine.attachStore(store)
            coords.isNotEmpty()
        }

    /**
     * When the metadata row is missing we still need to boot the engine before streaming tiles.
     * Reuse the engine's current origin/resolution as a conservative fallback and, when
     * possible, read the tile size from the first persisted tile to keep grid alignment.
     */
    private suspend fun deriveFallbackRasterMetadata(
        jobId: Long,
        engine: RasterCoverageEngine,
        coords: List<RasterTileCoord>
    ): JobRasterMetadata {
        val inferredTileSize = coords.firstOrNull()?.let { first ->
            rasterTileDao.getTile(jobId, first.tx, first.ty)?.tileSize
        }

        return JobRasterMetadata(
            jobId = jobId,
            originLat = engine.currentOriginLat(),
            originLon = engine.currentOriginLon(),
            resolutionM = engine.currentResolutionM(),
            tileSize = inferredTileSize ?: engine.currentTileSize()
        )
    }

    private fun buildRasterTotals(engine: RasterCoverageEngine): RasterTotals {
        val resolution = engine.currentResolutionM()
        val areas = engine.getAreas()
        val rateStats = engine.getRateStats()
        val m2PerPx = resolution * resolution
        val overlapPx = (areas.overlapM2 / m2PerPx).roundToLong().coerceAtLeast(0)
        val oncePx = (areas.effectiveM2 / m2PerPx).roundToLong().coerceAtLeast(0)

        return RasterTotals(
            totalOncePx = oncePx,
            totalOverlapPx = overlapPx,
            sectionPx = areas.bySectionM2.copyOf(),
            rateSumBySection = rateStats.sumBySection.copyOf(),
            rateCountBySection = rateStats.countBySection.copyOf(),
            rateSumByArea = rateStats.sumByArea,
            rateCountByArea = rateStats.countByArea
        )
    }
    suspend fun saveRasterMetadata(jobId: Long, engine: com.example.monitoragricola.raster.RasterCoverageEngine) =
        withContext(Dispatchers.IO) {

            val metadata = JobRasterMetadata(
                jobId = jobId,
                originLat = engine.currentOriginLat(),
                originLon = engine.currentOriginLon(),
                resolutionM = engine.currentResolutionM(),
                tileSize = engine.currentTileSize(),
                totals = buildRasterTotals(engine)
            )
            rasterMetadataDao.upsert(metadata.toEntity())
        }

    suspend fun listRasterTileCoords(jobId: Long): List<RasterTileCoord> =
        withContext(Dispatchers.IO) { rasterTileDao.listCoords(jobId) }
    suspend fun deleteRaster(jobId: Long) =
        withContext(Dispatchers.IO) {
            rasterTileDao.deleteByJob(jobId)
            rasterMetadataDao.deleteByJob(jobId)
        }

    suspend fun getRasterMetadata(jobId: Long): JobRasterMetadata? =
        withContext(Dispatchers.IO) { rasterMetadataDao.select(jobId)?.toDomain() }}
