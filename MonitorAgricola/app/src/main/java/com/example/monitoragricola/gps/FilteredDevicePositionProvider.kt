package com.example.monitoragricola.gps

import android.content.Context
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.IGpsPoseSource
import com.example.monitoragricola.gps.filter.GpsFilterPipeline
import com.example.monitoragricola.gps.filter.HeadingFilter
import com.example.monitoragricola.gps.filter.Kalman2D
import com.example.monitoragricola.gps.filter.OutlierGate
import com.example.monitoragricola.gps.filter.StationaryDetector
import com.example.monitoragricola.map.PositionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

class FilteredDevicePositionProvider(
    context: Context,
    private val scope: CoroutineScope,
    settings: GpsFilterSettings,
    clock: Clock = Clock.systemUTC(),
) : PositionProvider, IGpsPoseSource {

    private val leverArm = LeverArmCompensator(settings.antennaToImplementMeters, settings.lateralOffsetMeters)
    private val pipeline = GpsFilterPipeline(
        params = GpsFilterPipeline.Params(
            maxAccM = settings.maxAccM,
            maxJumpM = settings.maxJumpM,
            vHeadingMin = settings.vHeadingMin,
            emaAlphaLowSpeed = settings.emaAlphaLowSpeed,
            emaAlphaHighSpeed = settings.emaAlphaHighSpeed,
            antennaToImplementMeters = settings.antennaToImplementMeters,
            lateralOffsetMeters = settings.lateralOffsetMeters,
            articulatedModeEnabled = settings.articulatedModeEnabled,
            rateLimitHz = settings.rateLimitHz,
        ),
        kalman = Kalman2D(),
        headingFilter = HeadingFilter(
            vHeadingMin = settings.vHeadingMin,
            emaAlphaLowSpeed = settings.emaAlphaLowSpeed,
            emaAlphaHighSpeed = settings.emaAlphaHighSpeed,
            articulatedMode = settings.articulatedModeEnabled,
        ),
        outlierGate = OutlierGate(
            OutlierGate.Params(
                maxJumpM = settings.maxJumpM,
                maxAccelerationMps2 = 5.0,
                maxTurnRateDegPerSec = if (settings.articulatedModeEnabled) 90.0 else 120.0,
            )
        ),
        stationaryDetector = StationaryDetector(
            speedThreshold = settings.vHeadingMin,
            jitterThresholdM = if (settings.articulatedModeEnabled) 0.25 else 0.18,
        ),
        leverArm = leverArm,
        clock = clock,
    )

    private val deviceProvider = DeviceGpsProvider(context.applicationContext)
    private val poseSource = DeviceGpsPoseSource(deviceProvider, pipeline, scope)

    private val latestPoint = AtomicReference<GeoPoint?>()
    private val latestPose = AtomicReference<GpsPose?>()
    private var collectJob: Job? = null

    override val poses: Flow<GpsPose> = poseSource.poses

    override fun start() {
        if (collectJob != null) return
        poseSource.start()
        collectJob = scope.launch {
            poseSource.poses.collect { pose ->
                latestPose.set(pose)
                latestPoint.set(GeoPoint(pose.latitude, pose.longitude))
            }
        }
    }

    override fun stop() {
        collectJob?.cancel()
        collectJob = null
        poseSource.stop()
        latestPoint.set(null)
        latestPose.set(null)
    }

    override fun getCurrentPosition(): GeoPoint? = latestPoint.get()

    fun latestPose(): GpsPose? = latestPose.get()

    fun updateOffsets(longitudinal: Double, lateral: Double) {
        leverArm.updateOffsets(longitudinal, lateral)
    }

    fun setArticulatedMode(enabled: Boolean) {
        pipeline.setArticulatedMode(enabled)
    }
}