package com.example.monitoragricola.data

import android.content.Context

data class Implemento(
    val id: Int,
    val nome: String,
    val tipo: String,
    // Campos gerais
    val largura: Float = 0f,

    // Campos específicos para plantadeira
    val numLinhas: Int = 0,
    val espacamento: Float = 0f,

    // Campos específicos para pulverizador
    val tamanhoBarra: Float = 0f,
    val numSecoes: Int = 0,

    // Campos específicos para adubadora
    val capacidade: Float = 0f,
    val vazao: Float = 0f,

    // Campos específicos para colheitadeira
    val larguraColheita: Float = 0f,
    val tipoPlataforma: String = "",

    //campos para distancia entre antena e implemento
    val distanciaAntena: Float? = 0f,

    val offsetLateral: Float = 0f,  // negativo esquerda, positivo direita
    val offsetLongitudinal: Float = 0f, // frente/trás da antena

    val modoCadastro: String = "manual",

    // NOVOS CAMPOS
    val modoRastro: String? = null,                // "fixo" | "entrada" | "articulado"
    val distAntenaArticulacao: Float? = null,      // metros
    val distArticulacaoImplemento: Float? = null   // metros
) {
    companion object {
        private const val PREFS_NAME = "implemento_prefs"
        private const val KEY_LAST_ID = "last_id"

        fun getNextId(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastId = prefs.getInt(KEY_LAST_ID, 0)
            val nextId = lastId + 1
            prefs.edit().putInt(KEY_LAST_ID, nextId).apply()
            return nextId
        }

        fun getCamposPorTipo(tipo: String): List<Campo> {
            return when (tipo) {
                "Plantadeira" -> listOf(
                    Campo("Número de Linhas", "number", "numLinhas"),
                    Campo("Espaçamento (m)", "numberDecimal", "espacamento")
                )
                "Pulverizador" -> listOf(
                    Campo("Tamanho da Barra (m)", "numberDecimal", "tamanhoBarra"),
                    Campo("Número de Seções", "number", "numSecoes")
                )
                "Adubadora" -> listOf(
                    Campo("Capacidade (kg)", "numberDecimal", "capacidade"),
                    Campo("Vazão (kg/ha)", "numberDecimal", "vazao")
                )
                "Colheitadeira" -> listOf(
                    Campo("Largura de Colheita (m)", "numberDecimal", "larguraColheita"),
                    Campo("Tipo de Plataforma", "text", "tipoPlataforma")
                )
                else -> emptyList()
            }
        }
    }
}

data class Campo(
    val label: String,
    val inputType: String,
    val key: String
)