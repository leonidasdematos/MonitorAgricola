package com.example.monitoragricola.jobs

import com.example.monitoragricola.jobs.db.*
import com.example.monitoragricola.raster.RasterCoverageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class JobsRepository(
    private val jobDao: JobDao,
    private val pointDao: JobPointDao,
    private val eventDao: JobEventDao,
    private val rasterSnapDao: com.example.monitoragricola.raster.store.JobRasterSnapshotDao
) {
    private val rasterStore by lazy { com.example.monitoragricola.raster.store.RasterSnapshotStore(rasterSnapDao) }

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
        rasterSnapDao.deleteByJob(jobId)
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

    suspend fun loadRasterInto(jobId: Long, engine: com.example.monitoragricola.raster.RasterCoverageEngine): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) { rasterStore.loadInto(jobId, engine) }

    suspend fun saveRaster(jobId: Long, engine: com.example.monitoragricola.raster.RasterCoverageEngine) =
        withContext(kotlinx.coroutines.Dispatchers.IO) { rasterStore.save(jobId, engine) }
    suspend fun deleteRaster(jobId: Long) =
        withContext(kotlinx.coroutines.Dispatchers.IO) { rasterStore.delete(jobId) }
}
