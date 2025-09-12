package com.example.monitoragricola.raster

import android.graphics.Color

/**
 * Paletas simples para compor a imagem final do tile.
 * - byCount: 0 = transparente; 1 = verde; 2+ = vermelho
 * - (opcional) por seção / por taxa: hooks deixados para evoluir.
 */
object ColorLUT {
    private fun argb(a:Int, r:Int, g:Int, b:Int) = Color.argb(a, r, g, b)
    private val GREEN = argb(140, 0, 180, 0)
    private val RED   = argb(180, 255, 0, 0)
    const val TRANSPARENT = 0

    fun colorByCount(count: Int): Int {
        return when {
            count <= 0 -> TRANSPARENT
            count == 1 -> GREEN
            else -> RED
        }
    }
}
