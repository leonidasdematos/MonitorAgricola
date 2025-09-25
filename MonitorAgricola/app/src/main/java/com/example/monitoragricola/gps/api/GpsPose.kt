package com.example.monitoragricola.gps.api

import androidx.annotation.Keep

/**
 * Representa a pose filtrada do implemento.
 */
@Keep
data class GpsPose(
    val latitude: Double,
    val longitude: Double,
    val headingDeg: Double,
    val speedMps: Double,
    val accuracyM: Double,
    val timestampMillis: Long,
    val source: String = SOURCE_DEVICE_GPS,
)

const val SOURCE_DEVICE_GPS = "DEVICE_GPS"