package com.example.monitoragricola.hardware.gateway

import android.content.Context
import androidx.annotation.Keep

@Keep
data class GatewaySettings(
    val interpolateGatewayPoses: Boolean = true,
)

object GatewaySettingsPreferences {

    private const val PREFS_CONFIGS = "configs"
    private const val KEY_INTERPOLATE_POSES = "gateway_interpolate_poses"

    fun read(context: Context): GatewaySettings {
        val sp = context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE)
        return GatewaySettings(
            interpolateGatewayPoses = sp.getBoolean(KEY_INTERPOLATE_POSES, true),
        )
    }

    fun write(context: Context, settings: GatewaySettings) {
        context.getSharedPreferences(PREFS_CONFIGS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INTERPOLATE_POSES, settings.interpolateGatewayPoses)
            .apply()
    }
}