package com.example.monitoragricola.hardware.gateway

/**
 * Define como o monitor se conecta ao gateway Raspberry Pi.
 */
enum class GatewayConnectionMedium(val storageKey: String) {
    BLUETOOTH("bluetooth"),
    CABLE("cable");

    companion object {
        fun fromStorageKey(key: String?): GatewayConnectionMedium = when (key?.lowercase()) {
            CABLE.storageKey -> CABLE
            else -> BLUETOOTH
        }
    }
}

data class GatewayConnectionConfig(
    val medium: GatewayConnectionMedium,
    val endpoint: String? = null,
) {
    companion object {
        fun default(): GatewayConnectionConfig = GatewayConnectionConfig(GatewayConnectionMedium.CABLE, null)
    }
}