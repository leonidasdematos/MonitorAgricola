package com.example.monitoragricola.jobs.db


import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.monitoragricola.jobs.db.*                  // seus JobEntity/Point/Event
import com.example.monitoragricola.jobs.routes.db.*          // RouteEntity/RouteLineEntity
import com.example.monitoragricola.raster.store.JobRasterSnapshotDao
import com.example.monitoragricola.raster.store.JobRasterSnapshotEntity

@Database(
    entities = [
        JobEntity::class,
        JobPointEntity::class,
        JobEventEntity::class,
        JobRouteEntity::class,
        JobRouteLineEntity::class,
        JobRasterSnapshotEntity::class,       // << NOVO
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)            // << ver passo 4 se não existir
abstract class JobsDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun pointDao(): JobPointDao
    abstract fun eventDao(): JobEventDao
    abstract fun routeDao(): JobRouteDao


    // ❌ REMOVER antigos:
    // abstract fun jobRasterDeltaDao(): JobRasterDeltaDao

    // ✅ NOVO:
    abstract fun jobRasterSnapshotDao(): JobRasterSnapshotDao
}
