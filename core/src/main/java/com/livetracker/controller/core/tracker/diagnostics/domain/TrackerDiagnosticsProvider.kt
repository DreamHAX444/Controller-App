package com.livetracker.controller.core.tracker.diagnostics.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface TrackerDiagnosticsProvider {
    fun observeDiagnostics(deviceId: TrackerId): Flow<TrackerDiagnostics?>
    suspend fun getDiagnostics(deviceId: TrackerId): TrackerDiagnostics?
    fun observeHistory(deviceId: TrackerId): Flow<List<DiagnosticEvent>>
}
