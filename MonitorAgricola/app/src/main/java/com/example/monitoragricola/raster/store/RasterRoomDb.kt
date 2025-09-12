// com/example/monitoragricola/raster/store/RasterRoomDb.kt
package com.example.monitoragricola.raster.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RasterTileEntity::class], version = 1, exportSchema = false)
abstract class RasterRoomDb : RoomDatabase() {
    abstract fun rasterTileDao(): RasterTileDao
}
