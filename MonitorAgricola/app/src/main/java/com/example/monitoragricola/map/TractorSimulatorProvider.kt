package com.example.monitoragricola.map

import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class TractorSimulatorProvider(
    private val map: MapView,
    private val tractor: Marker
) : PositionProvider {

    val simulator = TractorSimulator(map, tractor)

    override fun start() {
        // Simulador não precisa de start próprio
    }

    override fun stop() {
        simulator.stop()
    }

    override fun getCurrentPosition(): GeoPoint? {
        simulator.update() // Atualiza posição e implemento
        return simulator.getPosition()
    }

    fun setImplemento(impl: Implemento?) {
        simulator.setImplemento(impl)
    }

    fun getImplementoAtual(): Implemento? {
        return simulator.getImplementoAtual()
    }
}