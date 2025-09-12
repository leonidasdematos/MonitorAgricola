// com/example/monitoragricola/raster/store/RasterSnapshotStore.kt
package com.example.monitoragricola.raster.store

import com.example.monitoragricola.raster.RasterCoverageEngine
import com.example.monitoragricola.raster.RasterSnapshot
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RasterSnapshotStore(
    private val dao: JobRasterSnapshotDao,
    private val gson: Gson = Gson()
) {
    suspend fun save(jobId: Long, engine: RasterCoverageEngine) {
        val snap: RasterSnapshot = engine.exportSnapshot()
        val raw: ByteArray = encodeSnapshot(snap)
        val gz: ByteArray = gzip(raw)
        dao.upsert(
            JobRasterSnapshotEntity(
                jobId = jobId,
                payload = gz,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadInto(jobId: Long, engine: RasterCoverageEngine): Boolean {
        val gz = dao.selectPayload(jobId) ?: return false
        val raw = gunzipSafe(gz) ?: gz
        val snap: RasterSnapshot = decodeSnapshot(raw)
        engine.importSnapshot(snap)
        return true
    }

    suspend fun delete(jobId: Long) {
        dao.deleteByJob(jobId)
    }

    // -------- Snapshot <-> ByteArray (sem depender de tipos internos) --------
    private fun encodeSnapshot(s: RasterSnapshot): ByteArray {
        val json = gson.toJson(s)
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeSnapshot(bytes: ByteArray): RasterSnapshot {
        val json = String(bytes, StandardCharsets.UTF_8)
        return gson.fromJson(json, RasterSnapshot::class.java)
    }

    // -------------------- GZIP --------------------
    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().use { baos ->
        GZIPOutputStream(baos).use { it.write(data) }
        baos.toByteArray()
    }

    private fun gunzipSafe(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (true) {
                val r = gz.read(tmp)
                if (r <= 0) break
                buf.write(tmp, 0, r)
            }
            buf.toByteArray()
        }
    } catch (_: Throwable) { null }
}
