// com/example/monitoragricola/jobs/db/Converters.kt
package com.example.monitoragricola.jobs.db

import androidx.room.TypeConverter
import com.example.monitoragricola.jobs.JobState

class Converters {
    @TypeConverter
    fun fromState(state: JobState): String = state.name

    @TypeConverter
    fun toState(name: String): JobState = JobState.valueOf(name)
}
