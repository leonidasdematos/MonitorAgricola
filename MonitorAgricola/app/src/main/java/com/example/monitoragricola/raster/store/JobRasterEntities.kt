// com/example/monitoragricola/raster/store/JobRasterEntity.kt
package com.example.monitoragricola.raster.store

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "job_raster")
data class JobRasterEntity(
    @PrimaryKey val jobId: Long,
    val metaJson: String,   // JSON com origin/resolution/tileSize etc.
    val payload: ByteArray  // blob com tiles serializados
)
