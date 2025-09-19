package com.example.monitoragricola

import com.example.monitoragricola.jobs.db.JobsDatabase
import android.app.Application
import androidx.room.Room
import com.example.monitoragricola.jobs.JobRecorder
import com.example.monitoragricola.jobs.JobManager
import com.example.monitoragricola.jobs.JobsRepository
import com.example.monitoragricola.map.ImplementoBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.monitoragricola.raster.store.RasterDatabase

const val FREE_MODE_JOB_ID = 0L

class App : Application() {

    data class FreeModeCoverage(
        val coveredOnceWkb: ByteArray?,
        val coveredOverlapWkb: ByteArray?
    )

    @Volatile
    var freeModeCoverage: FreeModeCoverage? = null

    // Escopo global da aplicação (para Room/IO/background leves)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val implementoStateStore = ImplementoStateStore()

    lateinit var freeModeTilePath: String


    fun clearFreeModeTileStore() {
        applicationScope.launch {
            rasterDb.rasterTileDao().deleteByJob(FREE_MODE_JOB_ID)
            rasterDb.jobRasterMetadataDao().deleteByJob(FREE_MODE_JOB_ID)
        }
    }

    // DB
    val db: JobsDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            JobsDatabase::class.java,
            "jobs.db" // mantém o mesmo nome
        )
            .fallbackToDestructiveMigration()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    // Banco Room para tiles raster por job
    val rasterDb: RasterDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            RasterDatabase::class.java,
            "raster_tiles.db"
        )
            .fallbackToDestructiveMigration()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }



    // Repo
    val jobsRepository: JobsRepository by lazy {
        JobsRepository(
            db.jobDao(),
            db.pointDao(),
            db.eventDao(),
            rasterDb
        )
    }

    // Recorder (amostra o rastro)
    val jobRecorder: JobRecorder by lazy {
        JobRecorder(
            repo = jobsRepository,
            externalScope = applicationScope,
            minDistanceMeters = 0.25, // ajuste se quiser
            minIntervalMs = 250
        )
    }

    // Manager (ciclo de vida + snapshots)
    val jobManager: JobManager by lazy {
        JobManager(
            repo = jobsRepository,
            recorder = jobRecorder,
            appScope = applicationScope
        )
    }
    // com/example/monitoragricola/App.kt
    val routesRepository by lazy {
        com.example.monitoragricola.jobs.routes.RoutesRepository(db.routeDao())
    }
    val routesManager by lazy {
        com.example.monitoragricola.jobs.routes.RoutesManager(routesRepository)
    }

    class ImplementoStateStore {
        private val map = mutableMapOf<Long, ImplementoBase.RuntimeState>()
        fun save(jobId: Long, state: ImplementoBase.RuntimeState) { map[jobId] = state }
        fun load(jobId: Long): ImplementoBase.RuntimeState? = map[jobId]
        fun clear(jobId: Long) { map.remove(jobId) }
        fun clearAll() { map.clear() }
    }




}
