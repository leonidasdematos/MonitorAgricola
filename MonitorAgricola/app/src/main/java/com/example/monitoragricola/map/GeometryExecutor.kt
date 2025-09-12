// ============================
// File: com/example/monitoragricola/map/GeometryExecutor.kt
// ============================
package com.example.monitoragricola.map

import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * Executor single-thread para todo trabalho JTS pesado.
 * Use GeometryExecutor.post { ... } para rodar fora da UI.
 */
object GeometryExecutor {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JTS-Worker").apply { priority = Thread.NORM_PRIORITY }
    }
    private val dispatcher = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun <T> post(block: suspend () -> T, onResult: (T) -> Unit = {}) {
        scope.launch {
            val res = block()
            withContext(Dispatchers.Main) { onResult(res) }
        }
    }

    fun shutdown() {
        exec.shutdown()
    }
}