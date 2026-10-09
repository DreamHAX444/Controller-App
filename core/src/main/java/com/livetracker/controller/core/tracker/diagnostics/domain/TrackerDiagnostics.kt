package com.livetracker.controller.core.tracker.diagnostics.domain

import com.livetracker.controller.core.tracker.domain.ConnectionState

data class TrackerDiagnostics(
    val connection: ConnectionDiagnostics,
    val recovery: RecoveryDiagnostics,
    val session: SessionDiagnostics,
    val health: HealthDiagnostics,
    val errors: ErrorDiagnostics
)

data class ConnectionDiagnostics(
    val connectionState: ConnectionState,
    val signalingState: String,
    val webrtcState: String,
    val heartbeatState: String
)

data class RecoveryDiagnostics(
    val state: String,
    val attemptCount: Int,
    val lastReason: String?,
    val lastFailure: String?,
    val lastSuccess: Long?
)

data class SessionDiagnostics(
    val generation: Int,
    val startTime: Long?,
    val lastEventTimestamp: Long?,
    val lastCommandTimestamp: Long?,
    val pendingCommandCount: Int
)

data class HealthDiagnostics(
    val battery: Int?,
    val networkState: String?,
    val lastHeartbeat: Long?,
    val lastKnownLocationTimestamp: Long?,
    val isStale: Boolean
)

data class ErrorDiagnostics(
    val lastCategory: String?,
    val timestamp: Long?,
    val message: String?,
    val isRecoverable: Boolean
)
