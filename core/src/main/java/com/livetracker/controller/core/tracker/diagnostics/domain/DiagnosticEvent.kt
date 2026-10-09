package com.livetracker.controller.core.tracker.diagnostics.domain

enum class DiagnosticEventType {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    RECOVERY_STARTED,
    RECOVERY_FAILED,
    RECOVERY_SUCCEEDED,
    HEARTBEAT_LOST,
    HEARTBEAT_RESTORED,
    DATA_CHANNEL_OPENED,
    DATA_CHANNEL_CLOSED,
    COMMAND_FAILED
}

data class DiagnosticEvent(
    val type: DiagnosticEventType,
    val timestamp: Long,
    val message: String? = null
)
