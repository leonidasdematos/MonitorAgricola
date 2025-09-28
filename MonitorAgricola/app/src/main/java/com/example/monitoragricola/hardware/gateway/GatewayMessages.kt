package com.example.monitoragricola.hardware.gateway

import com.example.monitoragricola.gps.api.GpsPose

/**
 * Capacidades que o gateway anuncia após a conexão.
 */
data class GatewayCapabilities(
    val supportsProgramUpdates: Boolean,
    val supportsTelemetry: Boolean,
    val supportsIsoBus: Boolean,
    val supportsSectionControl: Boolean,
)

/** Pacote de atualização enviado pelo monitor para o gateway. */
data class GatewayProgramUpdate(
    val programId: String,
    val version: String,
    val payload: ByteArray,
    val checksum: String,
)

/** Configuração do implemento que o gateway precisa para sincronizar offsets e seções. */
data class GatewayImplementConfiguration(
    val implementId: Int,
    val name: String,
    val widthMeters: Float,
    val rowCount: Int?,
    val rowSpacingMeters: Float?,
    val offsets: Offsets,
    val articulated: Boolean,
    val articulation: Articulation?,
    val hardwareManaged: Boolean,
) {
    data class Offsets(
        val lateralMeters: Float,
        val longitudinalMeters: Float,
    )

    data class Articulation(
        val antennaToJointMeters: Float,
        val jointToImplementMeters: Float,
    )
}

/** Telemetria enviada pelo gateway sobre o implemento em tempo real. */
data class GatewayImplementTelemetry(
    val isImplementActive: Boolean,
    val activeSectionsMask: Int,
    val rateValue: Float?,
    val timestampMillis: Long,
)

/** Comandos de seção enviados pelo monitor ao gateway. */
data class SectionControlCommand(
    val sectionsMask: Int,
    val enabled: Boolean,
)

/** Estrutura base para quadros ISOBUS encaminhados. */
data class IsoBusFrame(
    val pgn: Int,
    val priority: Int,
    val data: ByteArray,
)

sealed class GatewayCommand {
    data class PushProgram(val update: GatewayProgramUpdate) : GatewayCommand()
    data class ConfigureImplement(val config: GatewayImplementConfiguration) : GatewayCommand()
    object ClearImplement : GatewayCommand()
    data class SectionControl(val command: SectionControlCommand) : GatewayCommand()
    data class IsoBus(val frame: IsoBusFrame) : GatewayCommand()
    object RequestStatus : GatewayCommand()
}

sealed class GatewayInboundMessage {
    data class Pose(val pose: GpsPose) : GatewayInboundMessage()
    data class ImplementTelemetry(val telemetry: GatewayImplementTelemetry) : GatewayInboundMessage()
    data class Status(val capabilities: GatewayCapabilities, val programVersion: String?) : GatewayInboundMessage()
    data class ProgramAck(val programId: String, val version: String) : GatewayInboundMessage()
    data class IsoBus(val frame: IsoBusFrame) : GatewayInboundMessage()
    data class Log(val level: String, val message: String) : GatewayInboundMessage()
}