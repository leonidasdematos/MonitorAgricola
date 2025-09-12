package com.example.monitoragricola.raster

enum class RasterMode {
    COBERTURA,       // verde/vermelho padrão
    SOBREPOSICAO,    // só overlap
    VELOCIDADE,      // usa speed[]
    TAXA,            // usa rate[]
    SECOES           // colore por seção
}
