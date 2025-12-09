package com.example.monitoragricola.map

import com.example.monitoragricola.raster.RasterCoverageEngine
import kotlin.math.max


class Plantadeira(
    rasterEngine: RasterCoverageEngine,               // ⬅️ antes era AreaManager
    private var numLinhas: Int,
    private var espacamento: Float, // m
    distanciaAntena: Float = 0f,

    // do cadastro:
    val modoRastro: String? = null,               // "fixo" | "entrada" | "articulado"
    val distAntenaArticulacao: Float? = null,     // m (somente articulado)
    val distArticulacaoImplemento: Float? = null, // m (somente articulado)

    offsetLateral: Float = 0f,
    offsetLongitudinal: Float = 0f
) : ImplementoBase(rasterEngine, distanciaAntena, offsetLateral, offsetLongitudinal) {

    init {
        setPaintModel(PaintModel.fromKey(modoRastro))
    }

    private fun computeWidth(): Float = (numLinhas * espacamento).coerceAtLeast(0.05f)
    override fun getWorkWidthMeters(): Float = computeWidth()

    override fun getArticulationParameters(): ArticulationParameters? {
        val a = distAntenaArticulacao ?: return null
        val b = distArticulacaoImplemento ?: return null
        return ArticulationParameters(a.toDouble(), b.toDouble())
    }

    override fun updateConfig(numLinhas: Int, espacamento: Float) {
        this.numLinhas = max(1, numLinhas)
        this.espacamento = espacamento.coerceAtLeast(0.01f)
    }

    override fun getStatus(): Map<String, Any> = mapOf(
        "nome" to "Plantadeira",
        "numLinhas" to numLinhas,
        "espacamentoM" to espacamento,
        "larguraTrabalhoM" to computeWidth(),
        "distanciaAntenaM" to distanciaAntena,
        "offsetLateralM" to offsetLateral,
        "offsetLongitudinalM" to offsetLongitudinal,
        "modoRastro" to (modoRastro ?: "entrada")

    )

}
