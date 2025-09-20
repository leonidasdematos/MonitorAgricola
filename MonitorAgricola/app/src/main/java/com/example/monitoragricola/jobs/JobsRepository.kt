package com.example.monitoragricola.jobs

import com.example.monitoragricola.jobs.db.*
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.store.JobRasterMetadata
import com.example.monitoragricola.raster.store.RasterDatabase
import com.example.monitoragricola.raster.store.RasterTileCoord
import com.example.monitoragricola.raster.store.toDomain
import com.example.monitoragricola.raster.store.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.osmdroid.util.BoundingBox

class JobsRepository(
    private val jobDao: JobDao,
    private val pointDao: JobPointDao,
    private val eventDao: JobEventDao,
    private val rasterDb: RasterDatabase
) {

    private val rasterTileDao = rasterDb.rasterTileDao()
    private val rasterMetadataDao = rasterDb.jobRasterMetadataDao()

    private data class TileBounds(
        val minTx: Int,
        val maxTx: Int,
        val minTy: Int,
        val maxTy: Int
    )


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
            val persistedMetadata = rasterMetadataDao.select(jobId)?.toDomain()
            val effectiveMetadata = persistedMetadata ?: deriveFallbackRasterMetadata(jobId, engine, coords)
            val tileBounds = coords.takeIf { it.isNotEmpty() }?.let { existing ->
                TileBounds(
                    minTx = existing.minOf { it.tx },
                    maxTx = existing.maxOf { it.tx },
                    minTy = existing.minOf { it.ty },
                    maxTy = existing.maxOf { it.ty }
                )
            }

            suspend fun finalizeRestore() {
                withContext(Dispatchers.Default) {
                    engine.finishStoreRestore()
                    tileBounds?.let { bounds ->
                        val projection = engine.currentProjection()
                        if (projection != null) {
                            val tileSizeMeters = effectiveMetadata.tileSize.toDouble() * effectiveMetadata.resolutionM
                            val minXMeters = bounds.minTx.toDouble() * tileSizeMeters
                            val maxXMeters = (bounds.maxTx + 1).toDouble() * tileSizeMeters
                            val minYMeters = bounds.minTy.toDouble() * tileSizeMeters
                            val maxYMeters = (bounds.maxTy + 1).toDouble() * tileSizeMeters
                            val southWest = projection.toLatLon(Coordinate(minXMeters, minYMeters))
                            val northEast = projection.toLatLon(Coordinate(maxXMeters, maxYMeters))
                            val boundingBox = BoundingBox(
                                maxOf(southWest.latitude, northEast.latitude),
                                maxOf(southWest.longitude, northEast.longitude),
                                minOf(southWest.latitude, northEast.latitude),
                                minOf(southWest.longitude, northEast.longitude)
                            )
                            engine.updateViewport(boundingBox)
                        }
                    }
                }
            }

            withContext(Dispatchers.Default) {
                engine.startJob(
                    effectiveMetadata.originLat,
                    effectiveMetadata.originLon,
                    effectiveMetadata.resolutionM,
                    effectiveMetadata.tileSize
                )
            }
            var restoreStarted = false

            if (tileBounds != null) {
                val keys = coords.map { TileKey(it.tx, it.ty) }
                restoreStarted = true
                try {
                    store.preloadTiles(engine, keys)
                } catch (t: Throwable) {
                    engine.attachStore(store)
                    finalizeRestore()
                    throw t
                }
            }
            engine.attachStore(store)
            if (restoreStarted) {
                finalizeRestore()
            }
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
    suspend fun saveRaster(jobId: Long, engine: com.example.monitoragricola.raster.RasterCoverageEngine) =
        withContext(Dispatchers.IO) {
            val store = com.example.monitoragricola.raster.store.RoomTileStore(rasterDb, jobId)
            val dirty = engine.tilesSnapshot().mapNotNull { entry ->
                val keyPacked = entry.key
                val tile = entry.value
                if (tile.dirty) com.example.monitoragricola.raster.TileKey.unpack(keyPacked) to tile else null
            }
            store.saveDirtyTilesAndClear(dirty)
            for ((_, tile) in dirty) tile.dirty = false

            val metadata = JobRasterMetadata(
                jobId = jobId,
                originLat = engine.currentOriginLat(),
                originLon = engine.currentOriginLon(),
                resolutionM = engine.currentResolutionM(),
                tileSize = engine.currentTileSize()
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
