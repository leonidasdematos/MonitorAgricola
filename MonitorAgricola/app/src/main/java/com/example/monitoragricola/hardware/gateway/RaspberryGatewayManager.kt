package com.example.monitoragricola.hardware.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.SOURCE_EXTERNAL_GATEWAY

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
 * A implementação atual provê um barramento em memória e uma simulação simples
 * até que o transporte (Bluetooth/Cabo) seja implementado.
 */
class RaspberryGatewayManager(
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
    private var connectJob: Job? = null
    private var currentConfig: GatewayConnectionConfig? = null
    private var lastCapabilities: GatewayCapabilities = DEFAULT_CAPABILITIES
    private var lastProgramVersion: String? = null
    private var lastImplementConfig: GatewayImplementConfiguration? = null

    fun ensureConnected(config: GatewayConnectionConfig = GatewayConnectionConfig.default()) {
        scope.launch {
            stateMutex.withLock {
                if (_connectionState.value is GatewayConnectionState.Connected && currentConfig == config) {
                    return@launch
                }
                connectJob?.cancel()
                currentConfig = config
                connectJob = scope.launch {
                    _connectionState.value = GatewayConnectionState.Connecting(config)
                    try {
                        // Simula a negociação até termos transporte real.
                        delay(250)
                        _connectionState.value = GatewayConnectionState.Connected(
                            config = config,
                            capabilities = lastCapabilities,
                            programVersion = lastProgramVersion,
                        )
                        lastImplementConfig?.let { cfg ->
                            _outgoingCommands.emit(GatewayCommand.ConfigureImplement(cfg))
                        }
                    } catch (t: Throwable) {
                        _connectionState.value = GatewayConnectionState.Error(config, t)
                    }
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            stateMutex.withLock {
                connectJob?.cancel()
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
                // TODO: encaminhar para logger central.
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

    companion object {
        private val DEFAULT_CAPABILITIES = GatewayCapabilities(
            supportsProgramUpdates = true,
            supportsTelemetry = true,
            supportsIsoBus = true,
            supportsSectionControl = true,
        )
    }
}