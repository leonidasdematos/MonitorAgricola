package com.example.monitoragricola.hardware.ntrip

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.hardware.gateway.RaspberryGatewayManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cliente simples para consumir correções RTCM via protocolo NTRIP.
 */
class NtripClient(
    private val scope: CoroutineScope,
    private val gatewayManager: RaspberryGatewayManager,
) {

    private val _connectionState = MutableStateFlow<NtripConnectionState>(NtripConnectionState.Disconnected)
    val connectionState: StateFlow<NtripConnectionState> = _connectionState.asStateFlow()

    private var currentConfig: NtripConfig? = null
    private var connectionJob: Job? = null
    private val latestPose = AtomicReference<GpsPose?>()

    init {
        scope.launch {
            gatewayManager.poseFlow.collect { pose ->
                latestPose.set(pose)
            }
        }
    }

    fun ensureConnected(config: NtripConfig) {
        scope.launch {
            if (currentConfig == config && connectionState.value is NtripConnectionState.Connected) {
                return@launch
            }
            connectionJob?.cancelAndJoin()
            currentConfig = config
            connectionJob = scope.launch { runConnection(config) }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionJob?.cancelAndJoin()
            connectionJob = null
            currentConfig = null
            _connectionState.value = NtripConnectionState.Disconnected
        }
    }

    private suspend fun runConnection(config: NtripConfig) = coroutineScope {
        _connectionState.value = NtripConnectionState.Connecting(config)
        val socket = Socket()
        try {
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(config.host, config.port), SOCKET_TIMEOUT_MS)
            }
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            sendRequest(config, output)
            val header = readHeader(input)
            validateResponse(header)
            _connectionState.value = NtripConnectionState.Connected(config)
            maybeSendImmediateGga(output)
            val ggaJob = launch { pumpGgaLoop(output) }
            try {
                readLoop(input)
            } finally {
                ggaJob.cancelAndJoin()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "Erro na conexão NTRIP", t)
            _connectionState.value = NtripConnectionState.Error(config, t)
        } finally {
            withContext(NonCancellable) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                if (_connectionState.value !is NtripConnectionState.Error) {
                    _connectionState.value = NtripConnectionState.Disconnected
                }
            }
        }
    }

    private suspend fun sendRequest(config: NtripConfig, outputStream: OutputStream) {
        val mountPoint = config.mountPoint.trim().removePrefix("/")
        val auth = if (config.username.isNotBlank() || config.password.isNotBlank()) {
            val credentials = "${config.username}:${config.password}"
            val encoded = Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            "Authorization: Basic $encoded\r\n"
        } else {
            ""
        }
        val ggaHeader = latestGgaSentence(includeTerminator = false)
        val hostHeader = buildString {
            append(config.host)
            if (config.port != DEFAULT_NTRIP_PORT) {
                append(":")
                append(config.port)
            }
        }
        val request = buildString {
            append("GET /$mountPoint HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Ntrip-Version: Ntrip/2.0\r\n")
            append("User-Agent: MonitorAgricola/1.0\r\n")
            append("Connection: keep-alive\r\n")
            append(auth)
            if (ggaHeader != null) {
                append("Ntrip-GGA: $ggaHeader\r\n")
            }
            append("\r\n")
        }
        withContext(Dispatchers.IO) {
            outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            outputStream.flush()
        }
    }

    private suspend fun readHeader(inputStream: BufferedInputStream): String {
        val header = ByteArrayOutputStream()
        var lastFour = 0
        while (true) {
            val byte = withContext(Dispatchers.IO) { inputStream.read() }
            if (byte == -1) {
                throw IOException("Servidor NTRIP encerrou a conexão durante o cabeçalho")
            }
            header.write(byte)
            lastFour = ((lastFour shl 8) or (byte and 0xFF)) and 0xFFFFFFFF.toInt()
            if (lastFour == HEADER_DELIMITER) {
                break
            }
        }
        return header.toString(StandardCharsets.US_ASCII.name())
    }

    private fun validateResponse(header: String) {
        if (header.startsWith("ICY 200") || header.contains(" 200 OK")) {
            return
        }
        if (header.contains("SOURCETABLE", ignoreCase = true)) {
            throw IllegalStateException("Mountpoint inválido ou indisponível")
        }
        throw IllegalStateException("Resposta inesperada do servidor: ${header.lineSequence().firstOrNull()}")
    }

    private suspend fun readLoop(inputStream: BufferedInputStream) {
        val buffer = ByteArray(4096)
        while (true) {
            val read = withContext(Dispatchers.IO) { inputStream.read(buffer) }
            if (read == -1) {
                break
            }
            if (read == 0) {
                continue
            }
            val chunk = buffer.copyOf(read)
            gatewayManager.submitRtkCorrection(chunk)
        }
    }

    private suspend fun pumpGgaLoop(outputStream: OutputStream) {
        while (currentCoroutineContext().isActive) {
            sendLatestGga(outputStream)
            delay(GGA_INTERVAL_MS)
        }
    }

    private suspend fun maybeSendImmediateGga(outputStream: OutputStream) {
        sendLatestGga(outputStream)
    }

    private suspend fun sendLatestGga(outputStream: OutputStream) {
        val sentence = latestGgaSentence(includeTerminator = true) ?: return
        withContext(Dispatchers.IO) {
            outputStream.write(sentence.toByteArray(StandardCharsets.US_ASCII))
            outputStream.flush()
        }
    }

    private fun latestGgaSentence(includeTerminator: Boolean): String? {
        val pose = latestPose.get() ?: return null
        val now = System.currentTimeMillis()
        if (now - pose.timestampMillis > MAX_GGA_POSE_AGE_MS) {
            return null
        }
        return buildGgaSentence(pose, includeTerminator)
    }

    private fun buildGgaSentence(pose: GpsPose, includeTerminator: Boolean): String? {
        val latitude = pose.latitude
        val longitude = pose.longitude
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return null
        }
        val lat = formatCoordinate(latitude, true) ?: return null
        val lon = formatCoordinate(longitude, false) ?: return null
        val fixTime = formatUtcTime(pose.timestampMillis)
        val body = String.format(
            Locale.US,
            "GPGGA,%s,%s,%c,%s,%c,%d,%02d,%.1f,%.1f,M,0.0,M,,",
            fixTime,
            lat.value,
            lat.hemisphere,
            lon.value,
            lon.hemisphere,
            DEFAULT_GGA_FIX_QUALITY,
            DEFAULT_GGA_SATELLITES,
            DEFAULT_GGA_HDOP,
            DEFAULT_GGA_ALTITUDE_M,
        )
        val checksum = body.fold(0) { acc, c -> acc xor c.code }
        val sentence = buildString {
            append('$')
            append(body)
            append('*')
            append(String.format(Locale.US, "%02X", checksum and 0xFF))
        }
        return if (includeTerminator) {
            "$sentence\r\n"
        } else {
            sentence
        }
    }

    private data class NmeaCoordinate(val value: String, val hemisphere: Char)

    private fun formatCoordinate(value: Double, isLatitude: Boolean): NmeaCoordinate? {
        if (!value.isFinite()) {
            return null
        }
        val absValue = abs(value)
        val degrees = floor(absValue).toInt()
        val minutes = (absValue - degrees) * 60.0
        val hemisphere = if (isLatitude) {
            if (value >= 0) 'N' else 'S'
        } else {
            if (value >= 0) 'E' else 'W'
        }
        val formatted = if (isLatitude) {
            String.format(Locale.US, "%02d%07.4f", degrees, minutes)
        } else {
            String.format(Locale.US, "%03d%07.4f", degrees, minutes)
        }
        return NmeaCoordinate(formatted, hemisphere)
    }

    private fun formatUtcTime(timestampMillis: Long): String {
        val totalMillis = (timestampMillis % MILLIS_PER_DAY + MILLIS_PER_DAY) % MILLIS_PER_DAY
        val totalSeconds = totalMillis / 1000
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        val hundredths = ((totalMillis % 1000) / 10).toInt()
        return String.format(Locale.US, "%02d%02d%02d.%02d", hours, minutes, seconds, hundredths)
    }


    companion object {
        private const val TAG = "NtripClient"
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val HEADER_DELIMITER = 0x0D0A0D0A // \r\n\r\n
        private const val DEFAULT_NTRIP_PORT = 2101
        private const val GGA_INTERVAL_MS = 5_000L
        private const val MAX_GGA_POSE_AGE_MS = 30_000L
        private const val DEFAULT_GGA_FIX_QUALITY = 1
        private const val DEFAULT_GGA_SATELLITES = 12
        private const val DEFAULT_GGA_HDOP = 1.0
        private const val DEFAULT_GGA_ALTITUDE_M = 0.0
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

sealed class NtripConnectionState {
    object Disconnected : NtripConnectionState()
    data class Connecting(val config: NtripConfig) : NtripConnectionState()
    data class Connected(val config: NtripConfig) : NtripConnectionState()
    data class Error(val config: NtripConfig, val throwable: Throwable) : NtripConnectionState()
}

data class NtripConfig(
    val host: String,
    val port: Int,
    val mountPoint: String,
    val username: String,
    val password: String,
) {
    fun isValid(): Boolean {
        return host.isNotBlank() && mountPoint.isNotBlank()
    }
}

object NtripPreferences {
    private const val PREFS_NAME = "ntrip_prefs"
    private const val KEY_HOST = "ntrip_host"
    private const val KEY_PORT = "ntrip_port"
    private const val KEY_MOUNT = "ntrip_mount"
    private const val KEY_USER = "ntrip_user"
    private const val KEY_PASSWORD = "ntrip_password"

    fun save(context: Context, config: NtripConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_MOUNT, config.mountPoint)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    fun load(context: Context): NtripConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val port = prefs.getInt(KEY_PORT, 2101)
        val mount = prefs.getString(KEY_MOUNT, "") ?: ""
        val user = prefs.getString(KEY_USER, "") ?: ""
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        return NtripConfig(host, port, mount, user, password)
    }
}