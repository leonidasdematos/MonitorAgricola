package com.example.monitoragricola.raster.store

import androidx.room.Entity

/**
 * Chave composta (jobId, tx, ty).
 * Armazenamos um BLOB com todos os arrays do tile (codec próprio).
 */
@Entity(
    tableName = "raster_tiles",
    primaryKeys = ["jobId", "tx", "ty"]
)
data class RasterTileEntity(
    val jobId: Long,
    val tx: Int,
    val ty: Int,
    val rev: Int,
    val tileSize: Int,
    val layerMask: Int,
    val payload: ByteArray,
    val updatedAt: Long
)
