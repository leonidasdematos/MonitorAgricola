package com.example.monitoragricola.raster.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Chave composta (jobId, tx, ty).
 * Armazenamos um BLOB com todos os arrays do tile (codec próprio).
 */
@Entity(
    tableName = "raster_tiles",
    primaryKeys = ["jobId", "tx", "ty"]
)
data class RasterTileEntity(
    @PrimaryKey(autoGenerate = false)
    val id: Long,            // jobId<<32 ^ (tx & 0xffffffff) ^ (ty & 0xffffffffL) <<< ver helper abaixo
    val jobId: Long,
    val tx: Int,
    val ty: Int,
    val rev: Int,
    val tileSize: Int,
    val layerMask: Int,
    val payload: ByteArray,
    val updatedAt: Long
)
