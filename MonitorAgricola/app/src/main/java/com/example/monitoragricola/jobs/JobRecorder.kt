// com/example/monitoragricola/jobs/JobRecorder.kt
package com.example.monitoragricola.jobs

import com.example.monitoragricola.jobs.db.JobPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * JobRecorder
 * -----------
 * Responsável por gravar o rastro do trator (pontos) durante um Job "ativo".
 *
 * - Aplica amostragem por distância + intervalo mínimo (para evitar pontos em excesso).
 * - Você pode alterar a amostragem em runtime (setSampling), por exemplo, com base na velocidade.
 * - A inserção dos pontos é feita em Dispatcher.IO, assíncrona.
 */
class JobRecorder(
    private val repo: JobsRepository,
    private val externalScope: CoroutineScope,
    minDistanceMeters: Double = 0.25, // padrão (pode ser alterado dinamicamente)
    minIntervalMs: Long = 250L        // throttle temporal mínimo entre pontos
) {
    // ---- Configuração dinâmica de amostragem ----
    @Volatile
    private var _minDist = minDistanceMeters
    @Volatile
    private var _minInterval = minIntervalMs

    /**
     * Altera a amostragem em runtime.
     * Ex.: aumentar a distância mínima quando a velocidade for alta.
     */
    @Synchronized
    fun setSampling(minDistanceMeters: Double? = null, minIntervalMs: Long? = null) {
        minDistanceMeters?.let { _minDist = it }
        minIntervalMs?.let { _minInterval = it }
    }

    // ---- Estado interno para comparar com o último ponto gravado ----
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastT: Long = 0L
    private val seqCacheByJob: MutableMap<Long, Int> = mutableMapOf()

    /** Reinicia o estado interno (útil ao retomar depois de uma pausa). */
    fun reset() {
        lastLat = null
        lastLon = null
        lastT = 0L
        seqCacheByJob.clear()
    }

    /**
     * Chame este método no seu loop de posição **apenas quando houver Job ativo**.
     */
    fun onTick(
        jobId: Long,
        lat: Double,
        lon: Double,
        tMillis: Long,
        speedKmh: Float?,
        headingDeg: Float?
    ) {
        val now = tMillis
        if (now - lastT < _minInterval) return

        val prevLat = lastLat
        val prevLon = lastLon

        if (prevLat != null && prevLon != null) {
            val d = Geo.distanceMeters(prevLat, prevLon, lat, lon)
            if (d < _minDist) return
        }

        // Atualiza último ponto "aceito"
        lastLat = lat
        lastLon = lon
        lastT = now

        // Persiste no banco em IO
        externalScope.launch(Dispatchers.IO) {
            val nextSeq = (seqCacheByJob[jobId] ?: repo.nextSeq(jobId)).also {
                seqCacheByJob[jobId] = it + 1
            }
            repo.insertPoint(
                JobPointEntity(
                    jobId = jobId,
                    t = now,
                    lat = lat,
                    lon = lon,
                    speedKmh = speedKmh,
                    headingDeg = headingDeg,
                    seq = nextSeq
                )
            )
        }
    }
}
