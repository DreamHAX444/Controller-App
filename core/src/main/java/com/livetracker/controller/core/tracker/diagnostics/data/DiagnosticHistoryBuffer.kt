package com.livetracker.controller.core.tracker.diagnostics.data

import com.livetracker.controller.core.tracker.diagnostics.domain.DiagnosticEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiagnosticHistoryBuffer(
    private val maxEvents: Int = 100
) {
    private val history = ConcurrentHashMap<TrackerId, MutableStateFlow<List<DiagnosticEvent>>>()
    private val mutexes = ConcurrentHashMap<TrackerId, Mutex>()

    private fun getMutex(deviceId: TrackerId): Mutex {
        return mutexes.getOrPut(deviceId) { Mutex() }
    }

    suspend fun addEvent(deviceId: TrackerId, event: DiagnosticEvent) {
        val mutex = getMutex(deviceId)
        mutex.withLock {
            val flow = history.getOrPut(deviceId) { MutableStateFlow(emptyList()) }
            val current = flow.value
            val new = (current + event).takeLast(maxEvents)
            flow.value = new
        }
    }

    fun observeHistory(deviceId: TrackerId): Flow<List<DiagnosticEvent>> {
        return history.getOrPut(deviceId) { MutableStateFlow(emptyList()) }
    }
}
