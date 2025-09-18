package com.example.monitoragricola.jobs

import com.example.monitoragricola.jobs.db.*
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.store.RasterDatabase
import com.example.monitoragricola.raster.store.RasterTileCoord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class JobsRepository(
    private val jobDao: JobDao,
    private val pointDao: JobPointDao,
    private val eventDao: JobEventDao,
    private val rasterDb: RasterDatabase
) {

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
        rasterDb.rasterTileDao().deleteByJob(jobId)
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
            val coords = rasterDb.rasterTileDao().listCoords(jobId)
            var restoreStarted = false

            if (coords.isNotEmpty()) {
                val keys = coords.map { TileKey(it.tx, it.ty) }
                restoreStarted = true
                try {
                    store.preloadTiles(engine, keys)
                } catch (t: Throwable) {
                    engine.attachStore(store)
                    withContext(Dispatchers.Default) { engine.finishStoreRestore() }
                    throw t
                }
            }
            engine.attachStore(store)
            if (restoreStarted) {
                withContext(Dispatchers.Default) { engine.finishStoreRestore() }
            }
            coords.isNotEmpty()
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
        }

    suspend fun listRasterTileCoords(jobId: Long): List<RasterTileCoord> =
        withContext(Dispatchers.IO) { rasterDb.rasterTileDao().listCoords(jobId) }
    suspend fun deleteRaster(jobId: Long) =
        withContext(Dispatchers.IO) { rasterDb.rasterTileDao().deleteByJob(jobId) }
}
