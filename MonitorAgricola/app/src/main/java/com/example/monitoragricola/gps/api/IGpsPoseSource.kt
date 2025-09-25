package com.example.monitoragricola.gps.api

import kotlinx.coroutines.flow.Flow

interface IGpsPoseSource {
    val poses: Flow<GpsPose>
    fun start()
    fun stop()
}