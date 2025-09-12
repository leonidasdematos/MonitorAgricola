package com.example.monitoragricola.implementos

// Ajuste o import abaixo para o SEU data class de implemento do catálogo
// Ex.: import com.example.monitoragricola.data.implementos.Implemento
// Vou colocar um “placeholder” chamado ImplementoCatalog:
data class ImplementoCatalog(
    val id: Int,
    val nome: String,
    val tipo: String?,         // se não tiver, deixe null
    val numLinhas: Int?,       // para plantadeira
    val espacamentoM: Float,   // m
    val larguraTrabalhoM: Float?, // pode não existir hoje; tratamos abaixo
    val distanciaAntenaM: Float? = null,
    val offsetLateralM: Float? = null,
    val offsetLongitudinalM: Float? = null
)

/**
 * Calcula a largura de trabalho:
 * - para plantadeira: numLinhas * espacamento
 * - para outros: usa o campo larguraTrabalhoM se existir; se não, cai no espacamento (fallback mínimo)
 */
private fun calcularLarguraTrabalhoM(
    tipo: String?,
    numLinhas: Int?,
    espacamentoM: Float,
    larguraCatalogo: Float?
): Float {
    return when (tipo?.lowercase()) {
        "plantadeira" -> ((numLinhas ?: 1) * espacamentoM).coerceAtLeast(0.01f)
        else -> (larguraCatalogo ?: espacamentoM).coerceAtLeast(0.01f)
    }
}

/** Converte do objeto do catálogo para snapshot. */
fun ImplementoCatalog.toSnapshot(): ImplementoSnapshot {
    val largura = calcularLarguraTrabalhoM(tipo, numLinhas, espacamentoM, larguraTrabalhoM)
    return ImplementoSnapshot(
        id = id,
        nome = nome,
        tipo = tipo,
        numLinhas = numLinhas,
        espacamentoM = espacamentoM,
        larguraTrabalhoM = largura,
        distanciaAntenaM = distanciaAntenaM,
        offsetLateralM = offsetLateralM,
        offsetLongitudinalM = offsetLongitudinalM
    )
}