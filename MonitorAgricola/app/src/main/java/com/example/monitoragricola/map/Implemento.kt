package com.example.monitoragricola.map

import org.osmdroid.util.GeoPoint

interface Implemento {
    /** Inicia o implemento (simulação ou real) */
    fun start()

    /** Para o implemento */
    fun stop()

    /** Atualiza a posição do implemento com base na movimentação do trator */
    /**
     * Atualiza a posição do implemento com base na movimentação do trator.
     *
     * @param headingDeg rumo (0º = leste) fornecido externamente, se houver
     * @param speedMps velocidade do trator em m/s, usada para filtrar ruído em heading
     */
    fun updatePosition(
        last: GeoPoint?,
        current: GeoPoint,
        headingDeg: Float? = null,
        speedMps: Float? = null,
    )

    /** Retorna informações do implemento, ex: largura, linhas, tipo */
    fun getStatus(): Map<String, Any>

    open fun updateConfig(numLinhas: Int, espacamento: Float) {
        // Pode ser sobrescrito se o implemento precisar realmente usar esses valores
    }
}
