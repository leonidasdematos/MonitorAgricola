package com.example.monitoragricola.hardware.gateway

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persiste todas as mensagens trocadas com o gateway em arquivos de log.
 * Mantém apenas os últimos [RETENTION_DAYS] dias para evitar crescimento infinito.
 */
class GatewayConversationLogger(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val logDir = File(context.filesDir, "gateway_logs")
    private val logMutex = Mutex()

    init {
        ensureLogDirExists()
    }

    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var lastCleanupMillis: Long = 0L

    fun logMessage(direction: Direction, kind: String, message: String) {
        val now = System.currentTimeMillis()
        maybeScheduleCleanup(now)
        val line = formatLine(now, direction, kind, message)
        scope.launch(Dispatchers.IO) { persist(now, line) }
    }

    fun logDirectory(): File = logDir

    private fun formatLine(timestampMillis: Long, direction: Direction, kind: String, message: String): String {
        val timestamp = timestampFormatter.format(Date(timestampMillis))
        return "$timestamp [${direction.label}] $kind: $message"
    }

    private suspend fun persist(timestampMillis: Long, line: String) {
        withContext(Dispatchers.IO) {
            val file = File(logDir, "gateway-${dayFormatter.format(Date(timestampMillis))}.log")
            logMutex.withLock {
                ensureLogDirExists()
                file.appendText("$line\n")
            }
        }
    }

    private fun ensureLogDirExists() {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }


    private fun maybeScheduleCleanup(now: Long) {
        if (now - lastCleanupMillis < CLEANUP_INTERVAL_MILLIS) return
        lastCleanupMillis = now
        scope.launch(Dispatchers.IO) { cleanupOldLogs(now) }
    }

    private suspend fun cleanupOldLogs(referenceMillis: Long) {
        withContext(Dispatchers.IO) {
            val threshold = referenceMillis - RETENTION_MILLIS
            val files = logDir.listFiles() ?: return@withContext
            for (file in files) {
                if (file.lastModified() < threshold) {
                    file.delete()
                }
            }
        }
    }

    enum class Direction(val label: String) { INBOUND("IN"), OUTBOUND("OUT") }

    companion object {
        private const val RETENTION_DAYS = 7L
        private val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        private val CLEANUP_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}