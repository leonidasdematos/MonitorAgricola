package com.example.monitoragricola.gps

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep

@Keep
data class GpsFilterSettings(
    val maxAccM: Double = 8.0,
    val maxJumpM: Double = 6.0,
    val vHeadingMin: Double = 0.6,
    val emaAlphaLowSpeed: Double = 0.12,
    val emaAlphaHighSpeed: Double = 0.35,
    val antennaToImplementMeters: Double = 5.0,
    val lateralOffsetMeters: Double = 0.0,
    val articulatedModeEnabled: Boolean = true,
    val rateLimitHz: Double = 15.0,
)

object GpsFilterPreferences {

    private const val PREFS = "gps_filter"

    private const val KEY_MAX_ACC = "max_acc_m"
    private const val KEY_MAX_JUMP = "max_jump_m"
    private const val KEY_V_HEADING_MIN = "v_heading_min"
    private const val KEY_EMA_LOW = "ema_alpha_low"
    private const val KEY_EMA_HIGH = "ema_alpha_high"
    private const val KEY_ANTENNA_OFFSET = "antenna_offset_m"
    private const val KEY_LATERAL_OFFSET = "lateral_offset_m"
    private const val KEY_ARTICULATED = "articulated_mode"
    private const val KEY_RATE_LIMIT = "rate_limit_hz"

    fun read(context: Context): GpsFilterSettings {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GpsFilterSettings(
            maxAccM = sp.getDouble(KEY_MAX_ACC, 8.0),
            maxJumpM = sp.getDouble(KEY_MAX_JUMP, 6.0),
            vHeadingMin = sp.getDouble(KEY_V_HEADING_MIN, 0.6),
            emaAlphaLowSpeed = sp.getDouble(KEY_EMA_LOW, 0.12),
            emaAlphaHighSpeed = sp.getDouble(KEY_EMA_HIGH, 0.35),
            antennaToImplementMeters = sp.getDouble(KEY_ANTENNA_OFFSET, 5.0),
            lateralOffsetMeters = sp.getDouble(KEY_LATERAL_OFFSET, 0.0),
            articulatedModeEnabled = sp.getBoolean(KEY_ARTICULATED, true),
            rateLimitHz = sp.getDouble(KEY_RATE_LIMIT, 15.0),
        )
    }

    fun write(context: Context, settings: GpsFilterSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putDouble(KEY_MAX_ACC, settings.maxAccM)
            .putDouble(KEY_MAX_JUMP, settings.maxJumpM)
            .putDouble(KEY_V_HEADING_MIN, settings.vHeadingMin)
            .putDouble(KEY_EMA_LOW, settings.emaAlphaLowSpeed)
            .putDouble(KEY_EMA_HIGH, settings.emaAlphaHighSpeed)
            .putDouble(KEY_ANTENNA_OFFSET, settings.antennaToImplementMeters)
            .putDouble(KEY_LATERAL_OFFSET, settings.lateralOffsetMeters)
            .putBoolean(KEY_ARTICULATED, settings.articulatedModeEnabled)
            .putDouble(KEY_RATE_LIMIT, settings.rateLimitHz)
            .apply()
    }

    private fun SharedPreferences.getDouble(key: String, default: Double): Double =
        java.lang.Double.longBitsToDouble(getLong(key, java.lang.Double.doubleToRawLongBits(default)))

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
        putLong(key, java.lang.Double.doubleToRawLongBits(value))
}