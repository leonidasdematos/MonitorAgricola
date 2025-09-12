package com.example.monitoragricola.raster.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RasterTileEntity::class], version = 1)
abstract class RasterDatabase : RoomDatabase() {
    abstract fun rasterTileDao(): RasterTileDao
}