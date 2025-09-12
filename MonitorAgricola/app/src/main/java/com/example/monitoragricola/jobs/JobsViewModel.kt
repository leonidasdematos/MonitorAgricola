// com/example/monitoragricola/jobs/JobsViewModel.kt
package com.example.monitoragricola.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.monitoragricola.implementos.ImplementoSnapshot
import com.example.monitoragricola.jobs.db.JobEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JobsViewModel(
    private val repo: JobsRepository,
    private val manager: JobManager
) : ViewModel() {

    val jobsFlow = repo.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList<JobEntity>()
    )

    fun createAndStart(name: String, snapshot: ImplementoSnapshot, source: String, onCreated: (Long)->Unit) {
        viewModelScope.launch {
            val id = manager.createAndStart(name, snapshot, source)
            onCreated(id)
        }
    }

    fun pause(jobId: Long) = viewModelScope.launch { manager.pause(jobId) }
    fun resume(jobId: Long) = viewModelScope.launch { manager.resume(jobId) }
    fun finish(jobId: Long, areaM2: Double?, overlapM2: Double?) =
        viewModelScope.launch { manager.finish(jobId, areaM2, overlapM2) }

    fun cancel(jobId: Long) = viewModelScope.launch { manager.cancel(jobId) }
    fun delete(jobId: Long) = viewModelScope.launch { manager.delete(jobId) }
}
