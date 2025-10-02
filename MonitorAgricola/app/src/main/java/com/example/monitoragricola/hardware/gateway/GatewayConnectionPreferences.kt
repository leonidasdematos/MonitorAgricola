package com.example.monitoragricola.hardware.gateway

import android.content.Context
import androidx.core.content.edit

object GatewayConnectionPreferences {
    private const val PREFS_CONFIGS = "configs"
    private const val KEY_MEDIUM = "gateway_medium"
    private const val KEY_ENDPOINT = "gateway_endpoint"
    private const val KEY_DEVICE_NAME = "gateway_device_name"

    data class StoredGatewaySelection(
        val medium: GatewayConnectionMedium,
        val endpoint: String?,
        val deviceName: String?,
    )

    fun saveSelection(
        context: Context,
        medium: GatewayConnectionMedium,
        endpoint: String?,
        deviceName: String?,
    ) {
        context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE).edit {
            putString(KEY_MEDIUM, medium.storageKey)
            putString(KEY_ENDPOINT, endpoint)
            putString(KEY_DEVICE_NAME, deviceName)
        }
    }

    fun clearSelection(context: Context) {
        context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE).edit {
            remove(KEY_MEDIUM)
            remove(KEY_ENDPOINT)
            remove(KEY_DEVICE_NAME)
        }
    }

    fun loadSelection(context: Context): StoredGatewaySelection? {
        val prefs = context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE)
        val mediumKey = prefs.getString(KEY_MEDIUM, null)
        val endpoint = prefs.getString(KEY_ENDPOINT, null)
        val deviceName = prefs.getString(KEY_DEVICE_NAME, null)
        val medium = GatewayConnectionMedium.fromStorageKey(mediumKey)
        if (medium == GatewayConnectionMedium.BLUETOOTH && endpoint.isNullOrBlank()) {
            return null
        }
        return StoredGatewaySelection(medium, endpoint, deviceName)
    }

    fun toConnectionConfig(selection: StoredGatewaySelection?): GatewayConnectionConfig? {
        selection ?: return null
        return GatewayConnectionConfig(selection.medium, selection.endpoint)
    }
}