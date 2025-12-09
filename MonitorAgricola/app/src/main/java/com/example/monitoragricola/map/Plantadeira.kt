package com.example.monitoragricola.map

import android.util.Log
import com.example.monitoragricola.raster.RasterCoverageEngine
import org.locationtech.jts.geom.Coordinate
import kotlin.math.*

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

    companion object {
        private const val DBG_ART = true       // habilite/disable
        private var dbgCounter = 0
        private fun d(tag: String, msg: String) {
            if (!DBG_ART) return
            // throttle: apenas 1 de cada 5 mensagens
            if ((dbgCounter++ % 5) == 0) Log.d(tag, msg)
        }
    }

    init {
        setPaintModel(PaintModel.fromKey(modoRastro))
    }

    private fun computeWidth(): Float = (numLinhas * espacamento).coerceAtLeast(0.05f)
    override fun getWorkWidthMeters(): Float = computeWidth()

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
        "modoRastro" to (modoRastro ?: "entrada"),
        "distAntenaArticulacao" to (distAntenaArticulacao ?: 0f),
        "distArticulacaoImplemento" to (distArticulacaoImplemento ?: 0f)
    )

    override fun computeArticulatedCenters(
        lastXY: Coordinate, curXY: Coordinate,
        fwdX: Double, fwdY: Double,
        rightX: Double, rightY: Double
    ): Pair<Coordinate, Coordinate>? {
        val a = distAntenaArticulacao?.toDouble() ?: return null
        val b = distArticulacaoImplemento?.toDouble() ?: return null

        // ponto da articulação (engate)
        fun hitch(c: Coordinate) = Coordinate(
            c.x - a * fwdX + offsetLateral.toDouble() * rightX,
            c.y - a * fwdY + offsetLateral.toDouble() * rightY
        )
        val lastH = hitch(lastXY)
        val curH  = hitch(curXY)
        rememberArticulationLocal(curH)

        // deslocamento do trator (plataforma)
        val tvx = curXY.x - lastXY.x
        val tvy = curXY.y - lastXY.y
        val ds  = hypot(tvx, tvy)

        // ---------- parâmetros de robustez ----------
        val DS_MIN_HEADING   = 0.03     // m: abaixo disso, heading do deslocamento é pouco confiável
        val ANG_DEADBAND_SIN = 0.002    // ~0.115°: abaixo disso, considera sem curva
        val RELAX            = 0.85     // mesmo ganho de antes
        val MAX_STEP_RAD     = Math.toRadians(1.2) // limite por iteração (~1.2°)
        // --------------------------------------------

        // heading alvo do trator:
        // - se deslocou o suficiente, use atan2(tvy,tvx)
        // - caso contrário, derive do fwd (estável) ou mantenha o anterior
        val thetaDisp = if (ds >= DS_MIN_HEADING) atan2(tvy, tvx) else null
        val thetaFwd  = atan2(fwdY, fwdX)
        val thetaT    = thetaDisp ?: thetaFwd

        // ângulo anterior do implemento
        val thetaPrev = implThetaRad ?: thetaT

        // erro angular (normalizado para [-pi,pi])
        fun wrap(a: Double): Double {
            var x = a
            while (x <= -Math.PI) x += 2.0 * Math.PI
            while (x >   Math.PI) x -= 2.0 * Math.PI
            return x
        }
        val err = wrap(thetaT - thetaPrev)

        // dTheta nominal (mesma fórmula que você usava), com deadband e limites
        val turnFactor = sin(err) // sensível a desvio real de direção
        val dThetaNom  = (ds / b) * turnFactor

        val dThetaFiltered = when {
            // bem na reta: ignore micro correções para não tremer
            abs(turnFactor) < ANG_DEADBAND_SIN -> 0.0
            else -> dThetaNom
        }

        // se quase parado, faça só um "leak" suave pro alvo; se andando, use ganho normal
        val step = if (ds < DS_MIN_HEADING)
            wrap(0.10 * err)          // leak de ~10% por iteração quando parado
        else
            RELAX * dThetaFiltered    // ganho normal quando há deslocamento

        // limite de passo por iteração
        val dTheta = step.coerceIn(-MAX_STEP_RAD, MAX_STEP_RAD)

        val thetaNew = wrap(thetaPrev + dTheta)
        implThetaRad = thetaNew

        val axPrev = cos(thetaPrev); val ayPrev = sin(thetaPrev)
        val axNew  = cos(thetaNew ); val ayNew  = sin(thetaNew )

        // posição do implemento (traseiro) ao longo do eixo do implemento
        val lastImpl = Coordinate(lastH.x - b * axPrev, lastH.y - b * ayPrev)
        val curImpl  = Coordinate(curH.x  - b * axNew,  curH.y  - b * ayNew)

        rememberArticulationState(a, b, axNew, ayNew)
        return lastImpl to curImpl
    }
    private fun Double.format3() = String.format("%.3f", this)

    override fun importRuntimeState(state: RuntimeState?) {
        super.importRuntimeState(state)
        if (getPaintModel() != PaintModel.ARTICULADO) return

        val theta = state?.thetaRad ?: return
        val a = distAntenaArticulacao?.toDouble() ?: return
        val b = distArticulacaoImplemento?.toDouble() ?: return

        val ax = cos(theta)
        val ay = sin(theta)
        rememberArticulationState(a, b, ax, ay)
    }

}
