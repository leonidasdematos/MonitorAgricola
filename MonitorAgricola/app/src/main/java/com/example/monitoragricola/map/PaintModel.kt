// com/example/monitoragricola/map/PaintModel.kt
package com.example.monitoragricola.map

enum class PaintModel(val key: String) {
    FIXO("fixo"),
    ENTRADA_COMPENSADA("entrada"),
    ARTICULADO("articulado");

    companion object {
        fun fromKey(k: String?): PaintModel = when (k?.lowercase()) {
            "fixo" -> FIXO
            "articulado" -> ARTICULADO
            "entrada", "entrada_compensada", "compensada", "", null -> ENTRADA_COMPENSADA
            else -> ENTRADA_COMPENSADA
        }
    }
}
