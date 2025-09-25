package com.example.monitoragricola.gps

import android.location.Location
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.gps.api.IGpsPoseSource
import com.example.monitoragricola.gps.filter.GpsFilterPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class DeviceGpsPoseSource(
    private val provider: DeviceGpsProvider,
    private val pipeline: GpsFilterPipeline,
    private val externalScope: CoroutineScope,
) : IGpsPoseSource {

    private val _poses = MutableSharedFlow<GpsPose>(extraBufferCapacity = 32)
    override val poses: Flow<GpsPose> = _poses.asSharedFlow()

    private var started = false
    private var channel: Channel<Location>? = null
    private var scope: CoroutineScope? = null
    private var processingJob: Job? = null

    override fun start() {
        if (started) return
        started = true
        val job = SupervisorJob()
        processingJob = job
        val processingScope = (externalScope + job + Dispatchers.Default)
        scope = processingScope
        val localChannel = Channel<Location>(capacity = Channel.BUFFERED)
        channel = localChannel
        processingScope.launch {
            for (location in localChannel) {
                val pose = pipeline.process(location)
                if (pose != null) {
                    if (!_poses.tryEmit(pose)) {
                        _poses.emit(pose)
                    }
                }
            }
        }
        provider.start { loc ->
            channel?.trySend(loc)
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        provider.stop()
        channel?.close()
        channel = null
        processingJob?.cancel()
        processingJob = null
        scope = null
        pipeline.reset()
    }
}