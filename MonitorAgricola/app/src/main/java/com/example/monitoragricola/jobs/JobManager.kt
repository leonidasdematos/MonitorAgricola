package com.example.monitoragricola.jobs

import com.example.monitoragricola.implementos.ImplementoSnapshot
import com.example.monitoragricola.jobs.db.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.TileStore

// import com.example.monitoragricola.jobs.coverage.RasterCoverageProvider // (se/quando usar métricas agregadas)

class JobManager(
    private val repo: JobsRepository,
    private val recorder: JobRecorder,
    private val appScope: CoroutineScope
) {
    private val gson = Gson()

    private fun parseAndValidateImplementoSnapshot(json: String?): ImplementoSnapshot {
        val nonEmpty = json?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Selecione um implemento antes de iniciar um trabalho.")
        val snap = try {
            gson.fromJson(nonEmpty, ImplementoSnapshot::class.java)
        } catch (t: Throwable) {
            throw IllegalArgumentException("Snapshot de implemento inválido (JSON).", t)
        }
        require(snap.larguraTrabalhoM > 0f) { "Largura de trabalho do implemento inválida (<= 0)." }
        require(snap.espacamentoM > 0f) { "Espaçamento entre linhas inválido (<= 0)." }
        return snap
    }

    suspend fun createAndStart(
        name: String,
        snapshot: ImplementoSnapshot,
        source: String
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val job = JobEntity(
            name = name,
            implementoSnapshotJson = gson.toJson(snapshot),
            source = source,
            state = JobState.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
        val jobId = repo.insert(job)
        repo.addEvent(JobEventEntity(jobId = jobId, t = now, type = JobEventType.START.name))
        jobId
    }

    suspend fun pause(jobId: Long) = withContext(Dispatchers.IO) {
        val job = repo.get(jobId) ?: return@withContext
        if (job.state != JobState.ACTIVE) return@withContext
        val now = System.currentTimeMillis()
        repo.update(job.copy(state = JobState.PAUSED, updatedAt = now))
        repo.addEvent(JobEventEntity(jobId = jobId, t = now, type = JobEventType.PAUSE.name))
    }

    suspend fun resume(jobId: Long) = withContext(Dispatchers.IO) {
        val job = repo.get(jobId) ?: return@withContext
        if (job.state != JobState.PAUSED) return@withContext
        val now = System.currentTimeMillis()
        repo.update(job.copy(state = JobState.ACTIVE, updatedAt = now))
        repo.addEvent(JobEventEntity(jobId = jobId, t = now, type = JobEventType.RESUME.name))
        recorder.reset()
    }

    suspend fun finish(jobId: Long, areaM2: Double?, overlapM2: Double?) = withContext(Dispatchers.IO) {
        val job = repo.get(jobId) ?: return@withContext
        if (job.state == JobState.COMPLETED || job.state == JobState.CANCELED) return@withContext
        val now = System.currentTimeMillis()
        repo.update(
            job.copy(
                state = JobState.COMPLETED,
                updatedAt = now,
                finishedAt = now,
                areaM2 = areaM2 ?: job.areaM2,
                overlapM2 = overlapM2 ?: job.overlapM2
            )
        )
        repo.addEvent(JobEventEntity(jobId = jobId, t = now, type = JobEventType.FINISH.name))
    }

    suspend fun cancel(jobId: Long) = withContext(Dispatchers.IO) {
        val job = repo.get(jobId) ?: return@withContext
        if (job.state == JobState.COMPLETED || job.state == JobState.CANCELED) return@withContext
        val now = System.currentTimeMillis()
        repo.update(job.copy(state = JobState.CANCELED, updatedAt = now, finishedAt = now))
        repo.addEvent(JobEventEntity(jobId = jobId, t = now, type = JobEventType.CANCEL.name))
    }

    suspend fun reopenAsNew(
        oldJobId: Long,
        newName: String? = null,
        copyTrack: Boolean = false,
        decimateMeters: Double = 0.5
    ): Long = withContext(Dispatchers.IO) {
        val old = repo.get(oldJobId) ?: return@withContext -1L
        if (old.state != JobState.COMPLETED) return@withContext -1L

        val snap = parseAndValidateImplementoSnapshot(old.implementoSnapshotJson)
        val now = System.currentTimeMillis()
        val newJob = JobEntity(
            name = newName ?: "${old.name} (retomado)",
            implementoSnapshotJson = gson.toJson(snap),
            source = old.source,
            state = JobState.ACTIVE,
            createdAt = now,
            updatedAt = now,
            areaM2 = old.areaM2,
            overlapM2 = old.overlapM2,
            boundsGeoJson = old.boundsGeoJson,
            version = old.version
        )
        val newId = repo.insert(newJob)

        // (Opcional) copiar trilha com decimação
        if (copyTrack) {
            copyTrackWithDecimation(oldJobId, newId, decimateMeters)
        }

        repo.addEvent(
            JobEventEntity(
                jobId = newId,
                t = now,
                type = JobEventType.REOPENED_FROM.name,
                payloadJson = """{"from": $oldJobId, "copyTrack": $copyTrack}"""
            )
        )
        return@withContext newId
    }

    private suspend fun copyTrackWithDecimation(
        oldJobId: Long,
        newJobId: Long,
        decimateMeters: Double
    ) {
        var lastLat: Double? = null
        var lastLon: Double? = null
        var nextSeq = 1
        val pageSize = 10_000
        var offset = 0
        while (true) {
            val batch = repo.loadPointsPaged(oldJobId, pageSize, offset)
            if (batch.isEmpty()) break
            val toInsert = mutableListOf<JobPointEntity>()
            for (p in batch) {
                val accept = when {
                    lastLat == null -> true
                    Geo.distanceMeters(lastLat!!, lastLon!!, p.lat, p.lon) >= decimateMeters -> true
                    else -> false
                }
                if (accept) {
                    toInsert.add(p.copy(id = 0L, jobId = newJobId, seq = nextSeq++))
                    lastLat = p.lat
                    lastLon = p.lon
                }
            }
            if (toInsert.isNotEmpty()) repo.insertPointsBulk(toInsert)
            offset += pageSize
        }
    }

    suspend fun delete(jobId: Long) = withContext(Dispatchers.IO) { repo.deleteJobCascade(jobId) }

    suspend fun getActive(): JobEntity? = withContext(Dispatchers.IO) { repo.getActive() }

    suspend fun get(jobId: Long): JobEntity? = withContext(Dispatchers.IO) { repo.get(jobId) }

    // ========================= RASTER (NOVO) =========================
    /**
     * Anexa o TileStore ao engine e retorna true se já existiam tiles persistidos.
     * Use na tela ao retomar um job.
     */
    suspend fun loadRasterInto(jobId: Long, engine: RasterCoverageEngine): Boolean =
        repo.loadRasterInto(jobId, engine)

    /**
     * Persiste os tiles sujos do engine. Chame em checkpoints (pause, background, etc.).
     */    /** Salva tiles sujos e snapshot de metadados. */
    suspend fun saveRaster(jobId: Long, store: TileStore, engine: RasterCoverageEngine) =
        withContext(Dispatchers.IO) {
            val dirty = engine.tilesSnapshot().mapNotNull { (k, t) ->
                if (t.dirty) TileKey.unpack(k) to t else null
            }
            if (dirty.isNotEmpty()) {
                store.saveDirtyTilesAndClear(dirty)
                dirty.forEach { it.second.dirty = false }
            }
            repo.saveRaster(jobId, engine)
        }

    /** Remove o raster persistido do job (ao apagar o job, por exemplo). */
    suspend fun deleteRaster(jobId: Long) = repo.deleteRaster(jobId)
    fun exportSnapshot(engine: RasterCoverageEngine) = engine.exportSnapshot()

}
