package com.example.monitoragricola.implementos

/**
 * Snapshot do implemento usado por um Job.
 * Fica salvo em JSON dentro do Job para manter coerência histórica.
 */
data class ImplementoSnapshot(
    val id: Int,
    val nome: String,
    val tipo: String?,            // "plantadeira", etc.
    val numLinhas: Int?,
    val espacamentoM: Float,
    val larguraTrabalhoM: Float,
    val distanciaAntenaM: Float? = null,
    val offsetLateralM: Float? = null,
    val offsetLongitudinalM: Float? = null,

    // NOVOS:
    val modoRastro: String? = null,                // "fixo" | "entrada" | "articulado"
    val distAntenaArticulacaoM: Float? = null,     // se articulado
    val distArticulacaoImplementoM: Float? = null  // se articulado
)
