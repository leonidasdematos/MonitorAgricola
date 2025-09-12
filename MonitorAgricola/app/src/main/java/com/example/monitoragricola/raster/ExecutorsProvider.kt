package com.example.monitoragricola.raster

import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Executor single-thread para raster (separado do seu GeometryExecutor/JTS).
 */
object ExecutorsProvider {
    private val rasterExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Raster-Worker").apply { priority = Thread.NORM_PRIORITY }
    }
    private val rasterDispatcher = rasterExec.asCoroutineDispatcher()
    val rasterScope: CoroutineScope = CoroutineScope(SupervisorJob() + rasterDispatcher)
}
