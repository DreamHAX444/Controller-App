package com.livetracker.controller.core.tracker.diagnostics.data

import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.diagnostics.domain.*
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.domain.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DefaultTrackerDiagnosticsProvider(
    private val sessionManager: TrackerSessionManager,
    private val deviceRepository: TrackerDeviceRepository,
    private val historyBuffer: DiagnosticHistoryBuffer
) : TrackerDiagnosticsProvider {

    override fun observeDiagnostics(deviceId: TrackerId): Flow<TrackerDiagnostics?> {
        val sessionFlow = sessionManager.getSession(deviceId)
        return deviceRepository.observeDevice(deviceId).map { device ->
            if (device == null) return@map null
            val sessionState = sessionFlow?.value
            
            TrackerDiagnostics(
                connection = ConnectionDiagnostics(
                    connectionState = device.connectionState,
                    signalingState = sessionState?.signalingState ?: "UNKNOWN",
                    webrtcState = sessionState?.webrtcState ?: "UNKNOWN",
                    heartbeatState = sessionState?.heartbeatState?.health?.name ?: "UNKNOWN"
                ),
                recovery = RecoveryDiagnostics(
                    state = "UNKNOWN", // In a real app, downcast transport to RecoveryManager to extract
                    attemptCount = 0,
                    lastReason = null,
                    lastFailure = null,
                    lastSuccess = null
                ),
                session = SessionDiagnostics(
                    generation = 0,
                    startTime = null,
                    lastEventTimestamp = sessionState?.lastEventTimestamp,
                    lastCommandTimestamp = null,
                    pendingCommandCount = 0
                ),
                health = HealthDiagnostics(
                    battery = device.batteryLevel,
                    networkState = device.networkState,
                    lastHeartbeat = device.lastHeartbeatTimestamp,
                    lastKnownLocationTimestamp = device.lastKnownLocationTimestamp,
                    isStale = device.connectionState == ConnectionState.DISCONNECTED || device.connectionState == ConnectionState.FAILED
                ),
                errors = ErrorDiagnostics(
                    lastCategory = sessionState?.lastError?.name,
                    timestamp = null,
                    message = sessionState?.lastError?.name,
                    isRecoverable = true
                )
            )
        }
    }

    override suspend fun getDiagnostics(deviceId: TrackerId): TrackerDiagnostics? {
        val device = deviceRepository.getDevice(deviceId) ?: return null
        val sessionState = sessionManager.getSession(deviceId)?.value
        
        return TrackerDiagnostics(
            connection = ConnectionDiagnostics(
                connectionState = device.connectionState,
                signalingState = sessionState?.signalingState ?: "UNKNOWN",
                webrtcState = sessionState?.webrtcState ?: "UNKNOWN",
                heartbeatState = sessionState?.heartbeatState?.health?.name ?: "UNKNOWN"
            ),
            recovery = RecoveryDiagnostics(
                state = "UNKNOWN",
                attemptCount = 0,
                lastReason = null,
                lastFailure = null,
                lastSuccess = null
            ),
            session = SessionDiagnostics(
                generation = 0,
                startTime = null,
                lastEventTimestamp = sessionState?.lastEventTimestamp,
                lastCommandTimestamp = null,
                pendingCommandCount = 0
            ),
            health = HealthDiagnostics(
                battery = device.batteryLevel,
                networkState = device.networkState,
                lastHeartbeat = device.lastHeartbeatTimestamp,
                lastKnownLocationTimestamp = device.lastKnownLocationTimestamp,
                isStale = device.connectionState == ConnectionState.DISCONNECTED || device.connectionState == ConnectionState.FAILED
            ),
            errors = ErrorDiagnostics(
                lastCategory = sessionState?.lastError?.name,
                timestamp = null,
                message = sessionState?.lastError?.name,
                isRecoverable = true
            )
        )
    }

    override fun observeHistory(deviceId: TrackerId): Flow<List<DiagnosticEvent>> {
        return historyBuffer.observeHistory(deviceId)
    }
}
