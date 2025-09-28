package com.example.monitoragricola.hardware.gateway

import com.example.monitoragricola.gps.GpsFilterSettings
import com.example.monitoragricola.implementos.ImplementoSnapshot

fun ImplementoSnapshot.toGatewayConfiguration(defaults: GpsFilterSettings): GatewayImplementConfiguration {
    val baseLong = defaults.antennaToImplementMeters.toFloat()
    val baseLat = defaults.lateralOffsetMeters.toFloat()
    val longitudinal = ((distanciaAntenaM ?: baseLong) + (offsetLongitudinalM ?: 0f))
    val lateral = offsetLateralM ?: baseLat
    val articulated = modoRastro?.equals("articulado", ignoreCase = true) == true
    val articulation = if (articulated) {
        GatewayImplementConfiguration.Articulation(
            antennaToJointMeters = distAntenaArticulacaoM ?: 0f,
            jointToImplementMeters = distArticulacaoImplementoM ?: 0f,
        )
    } else {
        null
    }

    return GatewayImplementConfiguration(
        implementId = id,
        name = nome,
        widthMeters = larguraTrabalhoM,
        rowCount = numLinhas,
        rowSpacingMeters = espacamentoM,
        offsets = GatewayImplementConfiguration.Offsets(
            lateralMeters = lateral,
            longitudinalMeters = longitudinal,
        ),
        articulated = articulated,
        articulation = articulation,
        hardwareManaged = hardwareManaged,
    )
}