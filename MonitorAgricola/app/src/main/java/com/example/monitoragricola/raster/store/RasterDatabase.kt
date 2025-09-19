package com.example.monitoragricola.raster.store

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RasterTileEntity::class,
        JobRasterMetadataEntity::class,
    ],
    version = 2
)
abstract class RasterDatabase : RoomDatabase() {
    abstract fun rasterTileDao(): RasterTileDao
    abstract fun jobRasterMetadataDao(): JobRasterMetadataDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS job_raster_metadata (
                        jobId INTEGER NOT NULL PRIMARY KEY,
                        originLat REAL NOT NULL,
                        originLon REAL NOT NULL,
                        resolutionM REAL NOT NULL,
                        tileSize INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}