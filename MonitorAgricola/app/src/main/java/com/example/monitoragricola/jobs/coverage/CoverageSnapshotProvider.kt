package com.example.monitoragricola.jobs.coverage

data class CoverageSnapshot(
    val coveredOnceWkb: ByteArray?,   // pode ser null se ainda não houver
    val coveredOverlapWkb: ByteArray?,
    val areaM2: Double?,              // métricas já calculadas por você
    val overlapM2: Double?,
    val boundsGeoJson: String?
)

interface CoverageSnapshotProvider {
    fun buildSnapshot(): CoverageSnapshot
}
