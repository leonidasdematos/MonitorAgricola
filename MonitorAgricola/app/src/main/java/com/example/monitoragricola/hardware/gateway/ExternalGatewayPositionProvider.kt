package com.example.monitoragricola.hardware.gateway

import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.IGpsPoseSource
import com.example.monitoragricola.map.PositionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapter que transforma o fluxo de poses do gateway em um [PositionProvider]
 * compatível com o restante da aplicação.
 */
class ExternalGatewayPositionProvider(
    private val gatewayManager: RaspberryGatewayManager,
    private val scope: CoroutineScope,
) : PositionProvider, IGpsPoseSource {

    private val latestPoint = AtomicReference<GeoPoint?>()
    private val latestPose = AtomicReference<GpsPose?>()
    private var collectJob: Job? = null

    override val poses: Flow<GpsPose> = gatewayManager.poseFlow

    override fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            gatewayManager.poseFlow.collect { pose ->
                latestPose.set(pose)
                latestPoint.set(GeoPoint(pose.latitude, pose.longitude))
            }
        }
    }

    override fun stop() {
        collectJob?.cancel()
        collectJob = null
        latestPoint.set(null)
        latestPose.set(null)
    }

    override fun getCurrentPosition(): GeoPoint? = latestPoint.get()

    fun latestPose(): GpsPose? = latestPose.get()
}