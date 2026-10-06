package com.livetracker.controller.core.tracker.domain

import kotlinx.coroutines.flow.StateFlow

interface TrackerSessionManager {
    fun registerTracker(deviceId: TrackerId, displayName: String)
    fun unregisterTracker(deviceId: TrackerId)
    fun clearSessions()

    fun getSession(deviceId: TrackerId): StateFlow<TrackerSessionState>?
    fun getAllSessions(): List<StateFlow<TrackerSessionState>>
    fun hasSession(deviceId: TrackerId): Boolean
    fun listSessions(): List<TrackerId>

    suspend fun startConnection(deviceId: TrackerId)
    suspend fun disconnect(deviceId: TrackerId)

    suspend fun sendCommand(command: TrackerCommand): CommandResult
}
