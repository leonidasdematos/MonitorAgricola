package com.example.monitoragricola.hardware.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.SOURCE_EXTERNAL_GATEWAY
import com.google.gson.Gson
import com.google.gson.JsonArray
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
import java.util.UUID
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


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
    private val conversationLogger = GatewayConversationLogger(context.applicationContext, scope)
    private val rtkCorrections = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        Log.i(TAG, "Logs do gateway serão gravados em ${conversationLogger.logDirectory().absolutePath}")
    }

    private var connectJob: Job? = null
    private var currentConfig: GatewayConnectionConfig? = null
    private var lastCapabilities: GatewayCapabilities = DEFAULT_CAPABILITIES
    private var lastProgramVersion: String? = null
    private var lastImplementConfig: GatewayImplementConfiguration? = null
    private var activeImplementId: Int? = null
    private var lastCourseFixForHeading: CourseFix? = null


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
        val resolved = try {
            resolveEndpoint(config)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "Falha ao resolver endpoint do gateway", t)
            _connectionState.value = GatewayConnectionState.Error(config, t)
            withContext(NonCancellable) { cleanupAfterDisconnect() }
            return
        }
        var tcpSocket: Socket? = null
        var bluetoothSocket: BluetoothSocket? = null
        try {
            val streams = when (resolved) {
                is ResolvedEndpoint.Tcp -> {
                    val socket = withContext(Dispatchers.IO) {
                        Socket().apply {
                            connect(InetSocketAddress(resolved.host, resolved.port), SOCKET_CONNECT_TIMEOUT_MS)
                            soTimeout = SOCKET_READ_TIMEOUT_MS
                        }
                    }
                    tcpSocket = socket
                    ConnectionStreams(
                        reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)),
                    )
                }
                is ResolvedEndpoint.Bluetooth -> {
                    val socket = openBluetoothSocket(resolved)
                    bluetoothSocket = socket
                    ConnectionStreams(
                        reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)),
                        writer = BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)),
                    )
                }
            }
            Log.i(TAG, "Conectado ao gateway ${resolved.describe()}")
            val writerMutex = Mutex()
            performHandshake(config, resolved, streams.reader, streams.writer, writerMutex)
            val rtkJob = scope.launch { pumpRtkCorrections(streams.writer, writerMutex) }
            try {
                listenLoop(config, resolved, streams.reader, streams.writer, writerMutex)
            } finally {
                rtkJob.cancelAndJoin()
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
            withContext(Dispatchers.IO) {
                try {
                    tcpSocket?.close()
                } catch (_: Throwable) {
                }
                try {
                    bluetoothSocket?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private data class ConnectionStreams(
        val reader: BufferedReader,
        val writer: BufferedWriter,
    )

    @SuppressLint("MissingPermission")
    private suspend fun openBluetoothSocket(endpoint: ResolvedEndpoint.Bluetooth): BluetoothSocket {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth indisponível")
        return withContext(Dispatchers.IO) {
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {
            }
            val socket = endpoint.device.createRfcommSocketToServiceRecord(GATEWAY_BLUETOOTH_UUID)
            socket.connect()
            socket
        }
    }

    private suspend fun performHandshake(
        config: GatewayConnectionConfig,
        endpoint: ResolvedEndpoint,
        reader: BufferedReader,
        writer: BufferedWriter,
        writerMutex: Mutex,
    ) {
        val helloPayload = JsonObject().apply {
            val subscribeArray = JsonArray().apply { add("telemetry/rtk") }
            add("subscribe", subscribeArray)
            add("subscriptions", JsonArray().apply { add("telemetry/rtk") })
        }
        sendMessageLocked(writer, writerMutex, "HELLO", helloPayload)

        var helloAck: GatewayHelloAckPayload? = null
        while (helloAck == null) {
            val line = reader.safeReadLine() ?: throw IllegalStateException("Gateway encerrou conexão durante HELLO")
            val obj = parseEnvelope(line)
            if (obj == null) {
                conversationLogger.logMessage(
                    GatewayConversationLogger.Direction.INBOUND,
                    "UNKNOWN",
                    line,
                )
                continue
            }
            conversationLogger.logMessage(
                GatewayConversationLogger.Direction.INBOUND,
                obj.type,
                line,
            )
            when (obj.type) {
                "HELLO_ACK" -> {
                    helloAck = gson.fromJson(obj.payload, GatewayHelloAckPayload::class.java)
                    val capabilities = parseCapabilities(helloAck?.capabilities ?: emptyList())
                    lastCapabilities = capabilities
                    lastProgramVersion = helloAck?.version
                    _connectionState.value = GatewayConnectionState.Connected(config, capabilities, helloAck?.version)
                    sendMessageLocked(writer, writerMutex, "INFO", JsonObject())
                }
                "PING" -> sendMessageLocked(writer, writerMutex, "PONG", JsonObject())
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
        writerMutex: Mutex,
    ) {
        lastCourseFixForHeading = null
        while (true) {
            val line = reader.safeReadLine() ?: break
            val envelope = parseEnvelope(line)
            if (envelope == null) {
                conversationLogger.logMessage(
                    GatewayConversationLogger.Direction.INBOUND,
                    "UNKNOWN",
                    line,
                )
                continue
            }
            conversationLogger.logMessage(
                GatewayConversationLogger.Direction.INBOUND,
                envelope.type,
                line,
            )
            when (envelope.type) {
                "PING" -> sendMessageLocked(writer, writerMutex, "PONG", JsonObject())
                "INFO" -> handleInfo(envelope.payload, config, endpoint)
                "GNSS_FIX" -> handleGnssFix(envelope.payload, writer, writerMutex)
                else -> Log.d(TAG, "Mensagem não tratada: ${envelope.type}")
            }
        }
        Log.i(TAG, "Loop do gateway encerrado")
    }

    private suspend fun handleGnssFix(payload: JsonElement?, writer: BufferedWriter, writerMutex: Mutex) {
        val obj = payload?.takeIf { it.isJsonObject }?.asJsonObject
        val sequence = obj?.get("sequence")?.let { element -> element.asLongOrNull() }
        var status = "accepted"
        if (obj == null) {
            status = "invalid_payload"
            Log.w(TAG, "GNSS_FIX com payload inválido: $payload")
        } else {
            try {
                val latitude = obj.get("latitude")?.asDoubleOrNull()
                val longitude = obj.get("longitude")?.asDoubleOrNull()
                if (latitude == null || longitude == null) {
                    throw IllegalArgumentException("GNSS_FIX sem latitude/longitude")
                }
                val timestamp = obj.get("timestamp_ms")?.asLongOrNull()
                    ?: System.currentTimeMillis()
                val fallbackHeading = updateCourseHeading(latitude, longitude, timestamp)
                val heading = obj.get("heading_deg")?.asDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: obj.get("heading")?.asDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: fallbackHeading
                    ?: Double.NaN
                val speed = obj.get("speed_mps")?.asDoubleOrNull()
                    ?: obj.get("speed")?.asDoubleOrNull()
                    ?: 0.0
                val accuracy = obj.get("accuracy_m")?.asDoubleOrNull()
                    ?: obj.get("accuracy")?.asDoubleOrNull()
                    ?: 0.0

                val pose = GpsPose(
                    latitude = latitude,
                    longitude = longitude,
                    headingDeg = heading,
                    speedMps = speed,
                    accuracyM = accuracy,
                    timestampMillis = timestamp,
                    source = SOURCE_EXTERNAL_GATEWAY,
                )
                scope.launch { _poseFlow.emit(pose) }

                obj.getAsJsonObject("implement")?.let { implement ->
                    val isActive = implement.get("active")?.asBooleanOrNull() ?: false
                    val sectionsMask = if (isActive) {
                        parseSectionsMask(implement.getAsJsonArray("sections"))
                    } else {
                        0
                    }
                    val rate = implement.get("rate_value")?.asFloatOrNull()
                        ?: implement.get("rate")?.asFloatOrNull()
                        ?: implement.get("rate_lph")?.asFloatOrNull()
                    val mode = implement.get("mode")?.asStringOrNull()?.lowercase()
                    val articulatedField = implement.get("articulated")?.asBooleanOrNull()
                    val hasArticulationPayload = implement.get("articulation") != null
                    val articulated = articulatedField
                        ?: (mode == "articulated" || hasArticulationPayload)
                    val articulation = if (articulated || hasArticulationPayload) {
                        parseArticulationPayload(implement)
                    } else {
                        null
                    }

                    _implementTelemetry.value = GatewayImplementTelemetry(
                        isImplementActive = isActive,
                        activeSectionsMask = sectionsMask,
                        rateValue = rate,
                        timestampMillis = timestamp,
                        mode = mode,
                        articulated = articulated,
                        articulation = articulation,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Falha ao processar GNSS_FIX", t)
                status = "error"
            }
        }

        if (sequence != null) {
            val ackPayload = JsonObject().apply {
                addProperty("sequence", sequence)
                addProperty("status", status)
                addProperty("timestamp_ms", System.currentTimeMillis())
            }
            sendMessageLocked(writer, writerMutex, "GNSS_ACK", ackPayload)
        } else {
            Log.w(TAG, "GNSS_FIX recebido sem sequence: $payload")
        }
    }

    fun submitRtkCorrection(payload: ByteArray) {
        if (_connectionState.value !is GatewayConnectionState.Connected) {
            return
        }
        if (!rtkCorrections.tryEmit(payload)) {
            scope.launch { rtkCorrections.emit(payload) }
        }
    }


    private fun parseSectionsMask(array: JsonArray?): Int {
        if (array == null) return 0
        var mask = 0
        val count = min(array.size(), 32)
        for (index in 0 until count) {
            val element = array[index]
            val active = when {
                element.isJsonPrimitive -> {
                    val primitive = element.asJsonPrimitive
                    when {
                        primitive.isBoolean -> primitive.asBoolean
                        primitive.isNumber -> primitive.asInt != 0
                        primitive.isString -> primitive.asString.toIntOrNull()?.let { it != 0 } ?: false
                        else -> false
                    }
                }
                else -> false
            }
            if (active) {
                mask = mask or (1 shl index)
            }
        }
        return mask
    }

    private fun parseArticulationPayload(obj: JsonObject): GatewayImplementTelemetry.Articulation? {
        val articulationObj = obj.getAsJsonObject("articulation")

        fun resolve(field: String): JsonElement? {
            return articulationObj?.get(field) ?: obj.get(field)
        }

        val antennaToJoint = resolve("antenna_to_articulation_m")?.asDoubleOrNull()
        val jointToTool = resolve("articulation_to_tool_m")?.asDoubleOrNull()
        val axis = resolve("axis")?.asDoubleListOrNull()
        val theta = resolve("theta_rad")?.asDoubleOrNull()
        val hasMotion = resolve("has_motion")?.asBooleanOrNull() ?: false
        val jointLatLon = resolve("joint_latlon")?.asDoubleListOrNull()
        val implementLatLon = resolve("implement_latlon")?.asDoubleListOrNull()
        val antennaLocal = resolve("antenna_xy_m")?.asDoubleListOrNull()
        val jointLocal = resolve("joint_xy_m")?.asDoubleListOrNull()
        val implementLocal = resolve("implement_xy_m")?.asDoubleListOrNull()

        if (
            antennaToJoint == null && jointToTool == null && axis == null && theta == null &&
            jointLatLon == null && implementLatLon == null && antennaLocal == null &&
            jointLocal == null && implementLocal == null && !hasMotion
        ) {
            return null
        }

        return GatewayImplementTelemetry.Articulation(
            antennaToJointMeters = antennaToJoint?.toFloat(),
            jointToImplementMeters = jointToTool?.toFloat(),
            antennaLocalX = antennaLocal?.getOrNull(0),
            antennaLocalY = antennaLocal?.getOrNull(1),
            jointLocalX = jointLocal?.getOrNull(0),
            jointLocalY = jointLocal?.getOrNull(1),
            implementLocalX = implementLocal?.getOrNull(0),
            implementLocalY = implementLocal?.getOrNull(1),
            jointLat = jointLatLon?.getOrNull(0),
            jointLon = jointLatLon?.getOrNull(1),
            implementLat = implementLatLon?.getOrNull(0),
            implementLon = implementLatLon?.getOrNull(1),
            axisX = axis?.getOrNull(0),
            axisY = axis?.getOrNull(1),
            thetaRad = theta,
            hasMotion = hasMotion,
        )
    }


    private fun updateCourseHeading(latitude: Double, longitude: Double, timestamp: Long): Double? {
        val previous = lastCourseFixForHeading
        lastCourseFixForHeading = CourseFix(latitude, longitude, timestamp)
        if (previous == null) return null
        if (timestamp <= previous.timestampMillis) {
            return null
        }

        val distance = GeoPoint(previous.latitude, previous.longitude)
            .distanceToAsDouble(GeoPoint(latitude, longitude))
        if (distance < MIN_HEADING_DISTANCE_METERS) {
            return null
        }

        val bearing = bearingBetween(previous.latitude, previous.longitude, latitude, longitude)
        return bearing
    }

    private fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0.0) {
            bearing += 360.0
        }
        return bearing
    }

    private fun JsonElement.asLongOrNull(): Long? = when {
        !isJsonPrimitive -> null
        else -> try {
            val primitive = asJsonPrimitive
            when {
                primitive.isNumber -> primitive.asLong
                primitive.isString -> primitive.asString.toLongOrNull()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.asDoubleOrNull(): Double? = when {
        !isJsonPrimitive -> null
        else -> try {
            val primitive = asJsonPrimitive
            when {
                primitive.isNumber -> primitive.asDouble
                primitive.isString -> primitive.asString.toDoubleOrNull()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.asFloatOrNull(): Float? = asDoubleOrNull()?.toFloat()

    private fun JsonElement.asBooleanOrNull(): Boolean? = when {
        !isJsonPrimitive -> null
        else -> try {
            val primitive = asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asInt != 0
                primitive.isString -> primitive.asString.toBooleanStrictOrNull()
                    ?: primitive.asString.toIntOrNull()?.let { it != 0 }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement?.asDoubleListOrNull(): List<Double>? {
        val array = this?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.size() == 0) return emptyList()
        val values = ArrayList<Double>(array.size())
        for (element in array) {
            val value = element.asDoubleOrNull() ?: return null
            values += value
        }
        return values
    }

    private fun JsonElement.asStringOrNull(): String? = when {
        !isJsonPrimitive -> null
        else -> try {
            asJsonPrimitive.asString
        } catch (_: Throwable) {
            null
        }
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

    private suspend fun pumpRtkCorrections(writer: BufferedWriter, writerMutex: Mutex) {
        rtkCorrections.collect { payload ->
            val payloadObj = JsonObject().apply {
                addProperty("timestamp_ms", System.currentTimeMillis())
                addProperty("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP))
            }
            sendMessageLocked(writer, writerMutex, "RTK_CORRECTION", payloadObj)
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
        conversationLogger.logMessage(
            GatewayConversationLogger.Direction.OUTBOUND,
            type,
            json,
        )
        withContext(Dispatchers.IO) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }
    }

    private suspend fun sendMessageLocked(
        writer: BufferedWriter,
        mutex: Mutex,
        type: String,
        payload: JsonElement,
    ) {
        mutex.withLock {
            sendMessage(writer, type, payload)
        }
    }

    private fun resolveEndpoint(config: GatewayConnectionConfig): ResolvedEndpoint {
        return when (config.medium) {
            GatewayConnectionMedium.CABLE -> {
                val endpoint = config.endpoint?.takeIf { it.isNotBlank() }
                val defaultHost = "127.0.0.1"
                val defaultPort = 7777
                if (endpoint == null) {
                    ResolvedEndpoint.Tcp(defaultHost, defaultPort)
                } else {
                    val parts = endpoint.split(":")
                    if (parts.size == 2) {
                        val host = parts[0].ifBlank { defaultHost }
                        val port = parts[1].toIntOrNull() ?: defaultPort
                        ResolvedEndpoint.Tcp(host, port)
                    } else {
                        ResolvedEndpoint.Tcp(endpoint, defaultPort)
                    }
                }
            }
            GatewayConnectionMedium.BLUETOOTH -> {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Bluetooth indisponível no dispositivo")
                val address = config.endpoint?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Nenhum dispositivo Bluetooth configurado")
                val device = try {
                    adapter.getRemoteDevice(address)
                } catch (t: IllegalArgumentException) {
                    throw IllegalStateException("Endereço Bluetooth inválido: $address", t)
                }
                val displayName = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                ResolvedEndpoint.Bluetooth(device, displayName)
            }
        }
    }

    private fun ResolvedEndpoint.describe(): String = when (this) {
        is ResolvedEndpoint.Tcp -> "$host:$port"
        is ResolvedEndpoint.Bluetooth -> {
            val label = displayName?.takeIf { it.isNotBlank() }
            label?.let { "$it (${device.address})" } ?: device.address
        }
    }

    private fun parseCapabilities(caps: List<String>): GatewayCapabilities {
        val normalized = caps.map { it.lowercase() }.toSet()
        val supportsProgramUpdates = normalized.any { entry ->
            entry == "program_update" || entry == "program_updates"
        }
        val supportsTelemetry = normalized.any { entry ->
            entry == "telemetry" || entry.startsWith("telemetry/")
        }
        val supportsIsoBus = normalized.any { entry ->
            entry == "isobus"
        }
        val supportsSectionControl = normalized.any { entry ->
            entry == "section_control" || entry == "section-control"
        }
        return GatewayCapabilities(
            supportsProgramUpdates = supportsProgramUpdates,
            supportsTelemetry = supportsTelemetry,
            supportsIsoBus = supportsIsoBus,
            supportsSectionControl = supportsSectionControl,
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

    sealed class ResolvedEndpoint {
        data class Tcp(val host: String, val port: Int) : ResolvedEndpoint()
        data class Bluetooth(val device: BluetoothDevice, val displayName: String?) : ResolvedEndpoint() {
            val address: String get() = device.address
        }
    }
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

    private data class CourseFix(
        val latitude: Double,
        val longitude: Double,
        val timestampMillis: Long,
    )


    companion object {
        private const val TAG = "GatewayManager"
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 0 // blocking
        private val GATEWAY_BLUETOOTH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val DEFAULT_CAPABILITIES = GatewayCapabilities(
            supportsProgramUpdates = true,
            supportsTelemetry = true,
            supportsIsoBus = true,
            supportsSectionControl = true,
        )

        private const val MIN_HEADING_DISTANCE_METERS = 0.05
    }
}