package com.livetracker.controller.core.tracker.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TrackerTransport {
    val deviceId: TrackerId
    val connectionState: StateFlow<ConnectionState>
    val events: Flow<TrackerEvent>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendCommand(command: TrackerCommand): CommandResult
}
