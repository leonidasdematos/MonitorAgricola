// com/example/monitoragricola/jobs/db/JobEntity.kt
package com.example.monitoragricola.jobs.db

import androidx.room.*
import com.example.monitoragricola.jobs.JobState

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val implementoSnapshotJson: String?,      // snapshot do implemento (imutável)
    val source: String,                        // "gps" | "rtk" | "simulador"
    val state: JobState,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val areaM2: Double? = null,                // cache para lista
    val overlapM2: Double? = null,             // cache para lista
    val boundsGeoJson: String? = null,         // bbox em GeoJSON (opcional)
    val notes: String? = null,
    val version: Int = 1
)

@Entity(
    tableName = "job_points",
    indices = [Index("jobId"), Index("seq")]
)
data class JobPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val jobId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float? = null,
    val headingDeg: Float? = null,
    val seq: Int
)

@Entity(
    tableName = "job_events",
    indices = [Index("jobId")]
)
data class JobEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val jobId: Long,
    val t: Long,
    val type: String,            // JobEventType.name
    val payloadJson: String? = null
)

/*@Entity(
    tableName = "job_geom",
    primaryKeys = ["jobId"]
)
data class JobGeomEntity(
    val jobId: Long,
    val coveredOnceWkb: ByteArray?,     // WKB da geometria (JTS)
    val coveredOverlapWkb: ByteArray?
)
*/