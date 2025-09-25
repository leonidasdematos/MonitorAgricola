package com.example.monitoragricola.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.HandlerThread
import com.google.android.gms.location.*

/**
 * Wrapper leve em torno do [FusedLocationProviderClient].
 */
class DeviceGpsProvider(
    context: Context,
    private val requestIntervalMs: Long = 100L,
    private val minUpdateIntervalMs: Long = 80L,
    private val maxUpdateDelayMs: Long = 250L,
) {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    private var callback: LocationCallback? = null
    private var handlerThread: HandlerThread? = null

    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit) {
        if (callback != null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, requestIntervalMs)
            .setMinUpdateIntervalMillis(minUpdateIntervalMs)
            .setMaxUpdateDelayMillis(maxUpdateDelayMs)
            .build()

        val thread = HandlerThread("DeviceGpsProvider")
        thread.start()
        handlerThread = thread

        val localCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val list = result.locations ?: return
                for (loc in list) {
                    onLocation(loc)
                }
            }
        }
        callback = localCallback

        client.requestLocationUpdates(request, localCallback, thread.looper)
    }

    fun stop() {
        val cb = callback ?: return
        client.removeLocationUpdates(cb)
        callback = null
        handlerThread?.quitSafely()
        handlerThread = null
    }
}