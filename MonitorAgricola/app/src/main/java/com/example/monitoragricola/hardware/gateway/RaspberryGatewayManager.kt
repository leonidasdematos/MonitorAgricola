package com.example.monitoragricola.hardware.gateway

import android.content.Context
import android.util.Log
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.SOURCE_EXTERNAL_GATEWAY
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


sealed class GatewayConnectionState {
    object Disconnected : GatewayConnectionState()
    data class Connecting(val config: GatewayConnectionConfig) : GatewayConnectionState()
    data class Connected(
        val config: GatewayConnectionConfig,
        val capabilities: GatewayCapabilities,
        val programVersion: String?,
    ) : GatewayConnectionState()
    data class Error(val config: GatewayConnectionConfig, val throwable: Throwable) : GatewayConnectionState()
}

/**
 * Orquestra a conversa entre o app e o gateway Raspberry Pi.
 * A implementação usa socket TCP conforme o protocolo do MA Gateway.
 */
class RaspberryGatewayManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _poseFlow = MutableSharedFlow<GpsPose>(replay = 1, extraBufferCapacity = 16)
    val poseFlow: SharedFlow<GpsPose> = _poseFlow.asSharedFlow()

    private val _implementTelemetry = MutableStateFlow<GatewayImplementTelemetry?>(null)
    val implementTelemetry: StateFlow<GatewayImplementTelemetry?> = _implementTelemetry.asStateFlow()

    private val _outgoingCommands = MutableSharedFlow<GatewayCommand>(extraBufferCapacity = 32)
    val outgoingCommands: SharedFlow<GatewayCommand> = _outgoingCommands.asSharedFlow()

    private val stateMutex = Mutex()
    private val gson = Gson()
    private val implementStore = GatewayImplementStore(context.applicationContext)

    private var connectJob: Job? = null
    private var currentConfig: GatewayConnectionConfig? = null
    private var lastCapabilities: GatewayCapabilities = DEFAULT_CAPABILITIES
    private var lastProgramVersion: String? = null
    private var lastImplementConfig: GatewayImplementConfiguration? = null
    private var activeImplementId: Int? = null


    fun ensureConnected(config: GatewayConnectionConfig = GatewayConnectionConfig.default()) {
        scope.launch {
            stateMutex.withLock {
                if (_connectionState.value is GatewayConnectionState.Connected && currentConfig == config) {
                    return@launch
                }
                connectJob?.cancelAndJoin()
                connectJob = scope.launch { runConnection(config) }
                currentConfig = config
            }
        }
    }

    fun disconnect() {
        scope.launch {
            stateMutex.withLock {
                connectJob?.cancelAndJoin()
                connectJob = null
                currentConfig = null
                _connectionState.value = GatewayConnectionState.Disconnected
            }
        }
    }

    suspend fun pushProgram(update: GatewayProgramUpdate) {
        _outgoingCommands.emit(GatewayCommand.PushProgram(update))
    }

    suspend fun sendImplementConfiguration(config: GatewayImplementConfiguration) {
        stateMutex.withLock { lastImplementConfig = config }
        _outgoingCommands.emit(GatewayCommand.ConfigureImplement(config))
    }

    suspend fun clearImplementConfiguration() {
        stateMutex.withLock { lastImplementConfig = null }
        _outgoingCommands.emit(GatewayCommand.ClearImplement)
    }

    suspend fun sendSectionCommand(command: SectionControlCommand) {
        _outgoingCommands.emit(GatewayCommand.SectionControl(command))
    }

    suspend fun sendIsoBusFrame(frame: IsoBusFrame) {
        _outgoingCommands.emit(GatewayCommand.IsoBus(frame))
    }

    fun requestStatus() {
        scope.launch { _outgoingCommands.emit(GatewayCommand.RequestStatus) }
    }

    fun onIncomingMessage(message: GatewayInboundMessage) {
        when (message) {
            is GatewayInboundMessage.Pose -> {
                val pose = message.pose.copy(source = SOURCE_EXTERNAL_GATEWAY)
                scope.launch { _poseFlow.emit(pose) }
            }
            is GatewayInboundMessage.ImplementTelemetry -> {
                _implementTelemetry.value = message.telemetry
            }
            is GatewayInboundMessage.Status -> {
                lastCapabilities = message.capabilities
                lastProgramVersion = message.programVersion
                val cfg = currentConfig
                if (cfg != null) {
                    _connectionState.value = GatewayConnectionState.Connected(cfg, lastCapabilities, lastProgramVersion)
                }
            }
            is GatewayInboundMessage.ProgramAck -> {
                lastProgramVersion = message.version
            }
            is GatewayInboundMessage.IsoBus -> {
                // Futuramente encaminhar para o subsistema ISOBUS.
            }
            is GatewayInboundMessage.Log -> {
                Log.d(TAG, "GatewayLog: ${message.level} ${message.message}")
            }
        }
    }

    /** Utilitário para injetar telemetria durante testes. */
    fun debugInjectTelemetry(telemetry: GatewayImplementTelemetry) {
        _implementTelemetry.value = telemetry
    }

    /** Utilitário para injetar poses durante testes ou simulação. */
    fun debugInjectPose(pose: GpsPose) {
        val stamped = pose.copy(source = SOURCE_EXTERNAL_GATEWAY)
        scope.launch { _poseFlow.emit(stamped) }
    }

    private suspend fun runConnection(config: GatewayConnectionConfig) {
        _connectionState.value = GatewayConnectionState.Connecting(config)
        val resolved = resolveEndpoint(config)
        var socket: Socket? = null
        try {
            socket = withContext(Dispatchers.IO) {
                Socket().apply {
                    connect(InetSocketAddress(resolved.host, resolved.port), SOCKET_CONNECT_TIMEOUT_MS)
                    soTimeout = SOCKET_READ_TIMEOUT_MS
                }
            }
            Log.i(TAG, "Conectado ao gateway ${resolved.host}:${resolved.port}")
            withContext(Dispatchers.IO) {
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
                    performHandshake(config, resolved, reader, writer)
                    listenLoop(config, resolved, reader, writer)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            Log.e(TAG, "Erro na conexão com gateway", t)
            _connectionState.value = GatewayConnectionState.Error(config, t)
        } finally {
            withContext(NonCancellable) {
                cleanupAfterDisconnect()
            }
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun performHandshake(
        config: GatewayConnectionConfig,
        endpoint: ResolvedEndpoint,
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        sendMessage(writer, "HELLO", JsonObject())

        var helloAck: GatewayHelloAckPayload? = null
        while (helloAck == null) {
            val line = reader.safeReadLine() ?: throw IllegalStateException("Gateway encerrou conexão durante HELLO")
            val obj = parseEnvelope(line) ?: continue
            when (obj.type) {
                "HELLO_ACK" -> {
                    helloAck = gson.fromJson(obj.payload, GatewayHelloAckPayload::class.java)
                    val capabilities = parseCapabilities(helloAck?.capabilities ?: emptyList())
                    lastCapabilities = capabilities
                    lastProgramVersion = helloAck?.version
                    _connectionState.value = GatewayConnectionState.Connected(config, capabilities, helloAck?.version)
                    sendMessage(writer, "INFO", JsonObject())
                }
                "PING" -> sendMessage(writer, "PONG", JsonObject())
                else -> Log.w(TAG, "Mensagem inesperada antes do HELLO_ACK: ${obj.type}")
            }
        }
        Log.i(TAG, "HELLO_ACK recebido: versao=${helloAck.version} caps=${helloAck.capabilities}")
    }

    private suspend fun listenLoop(
        config: GatewayConnectionConfig,
        endpoint: ResolvedEndpoint,
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        while (true) {
            val line = reader.safeReadLine() ?: break
            val envelope = parseEnvelope(line) ?: continue
            when (envelope.type) {
                "PING" -> sendMessage(writer, "PONG", JsonObject())
                "INFO" -> handleInfo(envelope.payload, config, endpoint)
                else -> Log.d(TAG, "Mensagem não tratada: ${envelope.type}")
            }
        }
        Log.i(TAG, "Loop do gateway encerrado")
    }

    private suspend fun handleInfo(payload: JsonElement?, config: GatewayConnectionConfig, endpoint: ResolvedEndpoint) {
        if (payload == null || !payload.isJsonObject) {
            Log.w(TAG, "Payload INFO inválido")
            return
        }
        val info = gson.fromJson(payload, GatewayInfoPayload::class.java)
        lastProgramVersion = info.version
        info.implement?.let { implementInfo ->
            val implementId = implementStore.upsertFromGateway(implementInfo, config, endpoint)
            val previousId = activeImplementId
            if (previousId != null && previousId != implementId) {
                implementStore.removeGatewayImplement(previousId)
            }
            activeImplementId = implementId
        }
    }

    private suspend fun cleanupAfterDisconnect() {
        activeImplementId?.let {
            implementStore.removeGatewayImplement(it)
        }
        activeImplementId = null
        _implementTelemetry.value = null
        if (_connectionState.value !is GatewayConnectionState.Error) {
            _connectionState.value = GatewayConnectionState.Disconnected
        }
    }

    private fun parseEnvelope(line: String): GatewayEnvelope? {
        return try {
            val root = gson.fromJson(line, JsonObject::class.java)
            val type = root.get("type")?.asString ?: return null
            val payload = root.get("payload")
            GatewayEnvelope(type, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao decodificar mensagem do gateway", t)
            null
        }
    }

    private suspend fun sendMessage(writer: BufferedWriter, type: String, payload: JsonElement) {
        val obj = JsonObject().apply {
            addProperty("type", type)
            add("payload", payload)
        }
        val json = gson.toJson(obj)
        withContext(Dispatchers.IO) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }
    }

    private fun resolveEndpoint(config: GatewayConnectionConfig): ResolvedEndpoint {
        val endpoint = config.endpoint?.takeIf { it.isNotBlank() }
        val defaultHost = "127.0.0.1"
        val defaultPort = 7777
        if (endpoint == null) {
            return ResolvedEndpoint(defaultHost, defaultPort)
        }
        val parts = endpoint.split(":")
        return if (parts.size == 2) {
            val host = parts[0].ifBlank { defaultHost }
            val port = parts[1].toIntOrNull() ?: defaultPort
            ResolvedEndpoint(host, port)
        } else {
            ResolvedEndpoint(endpoint, defaultPort)
        }
    }

    private fun parseCapabilities(caps: List<String>): GatewayCapabilities {
        val normalized = caps.map { it.lowercase() }.toSet()
        return GatewayCapabilities(
            supportsProgramUpdates = normalized.contains("program_update") || normalized.contains("program_updates"),
            supportsTelemetry = normalized.contains("telemetry"),
            supportsIsoBus = normalized.contains("isobus"),
            supportsSectionControl = normalized.contains("section_control") || normalized.contains("section-control"),
        )
    }

    private suspend fun BufferedReader.safeReadLine(): String? = withContext(Dispatchers.IO) {
        try {
            readLine()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "Erro lendo do gateway", t)
            null
        }
    }

    data class ResolvedEndpoint(val host: String, val port: Int)

    data class GatewayEnvelope(val type: String, val payload: JsonElement?)

    data class GatewayHelloAckPayload(
        val version: String?,
        val capabilities: List<String>?,
    )

    data class GatewayInfoPayload(
        val version: String?,
        @SerializedName("uptime_s") val uptimeSeconds: Long?,
        val implement: GatewayImplementInfo?,
    )

    data class GatewayImplementInfo(
        val id: Int?,
        val role: String?,
        val name: String?,
        val manufacturer: String?,
        val model: String?,
        @SerializedName("row_count") val rowCount: Int?,
        @SerializedName("row_spacing_m") val rowSpacingMeters: Double?,
        @SerializedName("hitch_to_tool_m") val hitchToToolMeters: Double?,
        val sections: List<GatewayImplementSection>?,
    )

    data class GatewayImplementSection(
        val kind: String?,
        val count: Int?,
        @SerializedName("supports_variable_rate") val supportsVariableRate: Boolean?,
        @SerializedName("width_m") val widthMeters: Double?,
    )


    companion object {
        private const val TAG = "GatewayManager"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 0 // blocking

        private val DEFAULT_CAPABILITIES = GatewayCapabilities(
            supportsProgramUpdates = true,
            supportsTelemetry = true,
            supportsIsoBus = true,
            supportsSectionControl = true,
        )
    }
}