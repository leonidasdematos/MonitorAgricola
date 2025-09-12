package com.example.monitoragricola.map

import android.util.Log
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.*

/**
 * Simulador de trator com movimento realista:
 * - Velocidade constante em linha reta
 * - Meia-volta (U-turn) com raio = larguraDeTrabalho/2 para “cair” certinho na próxima passada
 * - Largura de trabalho do implemento (prioriza "larguraTrabalho"; senão usa espacamento * numLinhas)
 * - Cabeçalho/azimute do talhão configurável (padrão 45°)
 * - Atualiza Marker (posição e rotação) e invalida o mapa
 *
 * Chame update() em um Handler/Coroutine com intervalo fixo (ex.: a cada 100 ms).
 */
class TractorSimulator(
    private val map: MapView,
    private val tractor: Marker
) {

    // ===========================
    // Parâmetros de simulação
    // ===========================
    var speedMps: Double = 1.5           // velocidade ~5,4 km/h (bem plausível para operação)
    var rowLengthMeters: Double = 60.0  // comprimento da passada
    var fieldAzimuthDeg: Double = 45.0   // direção das linhas do talhão (0 = leste, 90 = norte)

    // Passo de tempo usado por tick (se não for calcular via timestamp)
    private val dtSeconds: Double = 0.1  // 100 ms por atualização

    // Estados
    private enum class Mode { STRAIGHT, TURNING }
    private var mode: Mode = Mode.STRAIGHT
    private var headingRad: Double = Math.toRadians(fieldAzimuthDeg) // rumo atual
    private var distanceOnRow: Double = 0.0
    private var turnedAngleAbs: Double = 0.0
    private var turnLeftNext: Boolean = true // alterna lado da curva a cada passada
    private var turnRadiusMeters: Double = 2.0 // definido a cada virada = larguraTrabalho/2

    // Posição
    private var currentPosition: GeoPoint = GeoPoint(-23.4000, -54.2000)

    // Implemento atual
    private var implemento: Implemento? = null

    // Constantes geodésicas simples (aprox.)
    private val EARTH_RADIUS_M = 6_371_000.0
    private val RAD_TO_DEG = 180.0 / Math.PI
    private val DEG_TO_RAD = Math.PI / 180.0

    // ===========================
    // API pública
    // ===========================
    fun update() {
        // 1) Descobre largura de trabalho atual (m)
        val larguraTrabalho = workingWidthMeters()
        // Garantia de valor mínimo razoável
        val larguraOk = max(larguraTrabalho, 0.5)

        when (mode) {
            Mode.STRAIGHT -> stepStraight(dtSeconds)
            Mode.TURNING  -> stepTurn(dtSeconds, larguraOk)
        }

        // Atualiza Marker no mapa
        tractor.position = currentPosition
        tractor.rotation = Math.toDegrees(headingRad).toFloat()
        tractor.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        map.invalidate()

        // Caso queira verdebug simples:
        // Log.d("TractorSim", "mode=$mode lat=${currentPosition.latitude}, lon=${currentPosition.longitude}, hdg=${tractor.rotation}")
    }

    fun setImplemento(impl: Implemento?) {
        implemento?.stop()
        implemento = impl
    }

    fun getPosition(): GeoPoint = currentPosition
    fun stop() { implemento?.stop(); implemento = null }
    fun getImplementoAtual(): Implemento? = implemento

    // ===========================
    // Movimento em linha reta
    // ===========================
    private fun stepStraight(dt: Double) {
        val step = speedMps * dt
        // Avança na direção do heading atual
        moveMeters(step, headingRad)
        distanceOnRow += step

        if (distanceOnRow >= rowLengthMeters) {
            // Inicia meia-volta; raio = largura/2 para deslocar 1 passada
            val largura = workingWidthMeters()
            turnRadiusMeters = max(largura / 2, 0.25)
            turnedAngleAbs = 0.0
            mode = Mode.TURNING
        }
    }

    // ===========================
    // Meia-volta suave (U-turn)
    // ===========================
    private fun stepTurn(dt: Double, larguraOk: Double) {
        // Curvatura constante: ω = v/R
        val omega = speedMps / turnRadiusMeters  // rad/s
        val dTheta = omega * dt                  // variação de rumo para este passo

        // Define sentido (esquerda CCW positivo, direita CW negativo)
        val sgn = if (turnLeftNext) +1.0 else -1.0
        val dHeading = sgn * dTheta

        // Integra rumo e posição (modelo bicicleta simplificado)
        headingRad = wrapAngle(headingRad + dHeading)

        // Mover adiante após atualizar heading para seguir o arco
        val step = speedMps * dt
        moveMeters(step, headingRad)

        turnedAngleAbs += abs(dTheta)

        // Completar meia-volta quando girar ~π rad (180°)
        if (turnedAngleAbs >= Math.PI) {
            // Terminou a curva: pronto para nova passada paralela
            mode = Mode.STRAIGHT
            distanceOnRow = 0.0
            turnedAngleAbs = 0.0
            // Inverte lado da próxima curva (serpentina)
            turnLeftNext = !turnLeftNext

            // Após meia-volta o heading já está oposto (~180°)
            // Opcional: “snap” fino para alinhar exatamente à direção oposta das linhas
            val base = Math.toRadians(fieldAzimuthDeg)
            headingRad = snapToParallel(headingRad, base)
        }
    }

    // ===========================
    // Helpers geodésicos e util
    // ===========================
    private fun moveMeters(distance: Double, bearingRad: Double) {
        // dx para Leste, dy para Norte
        val dx = distance * cos(bearingRad)
        val dy = distance * sin(bearingRad)

        val latRad = currentPosition.latitude * DEG_TO_RAD
        val dLatDeg = (dy / EARTH_RADIUS_M) * RAD_TO_DEG
        val dLonDeg = (dx / (EARTH_RADIUS_M * cos(latRad))) * RAD_TO_DEG

        val newLat = currentPosition.latitude + dLatDeg
        val newLon = currentPosition.longitude + dLonDeg
        currentPosition = GeoPoint(newLat, newLon)
    }

    private fun wrapAngle(a: Double): Double {
        var x = a
        while (x <= -Math.PI) x += 2.0 * Math.PI
        while (x > Math.PI) x -= 2.0 * Math.PI
        return x
    }

    /**
     * Ajusta o heading para ficar exatamente paralelo às linhas (0° ou 180° a partir de base)
     */
    private fun snapToParallel(hdg: Double, base: Double): Double {
        val a = wrapAngle(hdg - base)
        // se estiver mais perto de 0° ou 180°
        return if (abs(a) < abs(abs(a) - Math.PI)) base + a.sign * 0.0
        else wrapAngle(base + Math.PI)
    }

    /**
     * Largura de trabalho do implemento:
     * - tenta "larguraTrabalho" (m)
     * - senão, usa espacamento * numLinhas (m)
     * - default 2.7 m (ex.: 6 linhas x 0,45 m)
     */
    private fun workingWidthMeters(): Double {
        val st = implemento?.getStatus()
        // prioridade: larguraTrabalho
        (st?.get("larguraTrabalho") as? Number)?.toDouble()?.let { if (it > 0.05) return it }

        val espac = (st?.get("espacamento") as? Number)?.toDouble() ?: 0.45
        val linhas = (st?.get("numLinhas") as? Number)?.toInt() ?: 6
        val width = espac * linhas
        return if (width > 0.05) width else 2.7
    }
}
