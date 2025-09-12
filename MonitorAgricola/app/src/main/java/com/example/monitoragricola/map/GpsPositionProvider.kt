package com.example.monitoragricola.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import org.osmdroid.util.GeoPoint

class GpsPositionProvider(private val context: Context) : PositionProvider {

    private var locationManager: LocationManager? = null
    private var currentPosition: GeoPoint? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentPosition = GeoPoint(location.latitude, location.longitude)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Pega última posição conhecida (pra não começar nulo)
        locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
            currentPosition = GeoPoint(loc.latitude, loc.longitude)
        }

        // Começa a receber atualizações
        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L, // intervalo mínimo (1s)
            0f,    // distância mínima
            listener
        )
    }

    override fun stop() {
        locationManager?.removeUpdates(listener)
    }

    override fun getCurrentPosition(): GeoPoint? {
        return currentPosition
    }
}

