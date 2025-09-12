// com/example/monitoragricola/jobs/routes/db/JobRouteEntity.kt
package com.example.monitoragricola.jobs.routes.db

import androidx.room.*
import com.example.monitoragricola.jobs.routes.RouteState
import com.example.monitoragricola.jobs.routes.RouteType

// com/example/monitoragricola/jobs/routes/db/JobRouteEntity.kt
@Entity(tableName = "job_routes", indices = [Index("jobId"), Index("state")])
data class JobRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val type: RouteType,
    val state: RouteState = RouteState.ACTIVE,

    // ⬇️ NOVO: origem da projeção usada para gerar/armazenar XY
    val originLat: Double,
    val originLon: Double,

    // parâmetros gerais
    val spacingM: Float,
    val widthM: Float?,
    val createdAt: Long,

    // AB reta
    val aLat: Double? = null, val aLon: Double? = null,
    val bLat: Double? = null, val bLon: Double? = null,
    val headingDeg: Float? = null,

    // curva (trilho de referência opcional)
    val refStartT: Long? = null,
    val refEndT: Long? = null,

    // (opcional) se você depois quiser guardar por sequência
    val refStartSeq: Int? = null,
    val refEndSeq: Int? = null
)


@Entity(
    tableName = "job_route_lines",
    indices = [Index("routeId"), Index("idx")],
    foreignKeys = [ForeignKey(
        entity = JobRouteEntity::class,
        parentColumns = ["id"],
        childColumns = ["routeId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class JobRouteLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val idx: Int,                     // 0 = linha central (AB), ±1, ±2 ... offsets
    val wkbLine: ByteArray            // LineString em WKB (JTS)
)
