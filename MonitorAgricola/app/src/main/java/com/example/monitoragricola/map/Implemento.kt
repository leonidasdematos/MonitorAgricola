package com.example.monitoragricola.map

import org.osmdroid.util.GeoPoint

interface Implemento {
    /** Inicia o implemento (simulação ou real) */
    fun start()

    /** Para o implemento */
    fun stop()

    /** Atualiza a posição do implemento com base na movimentação do trator */
    fun updatePosition(last: GeoPoint?, current: GeoPoint)

    /** Retorna informações do implemento, ex: largura, linhas, tipo */
    fun getStatus(): Map<String, Any>

    open fun updateConfig(numLinhas: Int, espacamento: Float) {
        // Pode ser sobrescrito se o implemento precisar realmente usar esses valores
    }
}
