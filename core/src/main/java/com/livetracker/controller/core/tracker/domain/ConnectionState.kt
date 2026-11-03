package com.livetracker.controller.core.tracker.domain

enum class ConnectionState {
    REGISTERED,
    CONNECTING,
    SIGNALING,
    WEBRTC_CONNECTING,
    DATA_CHANNEL_OPENING,
    CONNECTED,
    DEGRADED,
    RECONNECTING,
    SIGNALING_RECOVERY,
    WEBRTC_RECOVERY,
    DATA_CHANNEL_RECOVERY,
    FAILED,
    DISCONNECTED
}

fun ConnectionState.isConnectingOrRecovering(): Boolean = when (this) {
    ConnectionState.CONNECTING,
    ConnectionState.SIGNALING,
    ConnectionState.WEBRTC_CONNECTING,
    ConnectionState.DATA_CHANNEL_OPENING,
    ConnectionState.RECONNECTING,
    ConnectionState.SIGNALING_RECOVERY,
    ConnectionState.WEBRTC_RECOVERY,
    ConnectionState.DATA_CHANNEL_RECOVERY -> true
    else -> false
}

