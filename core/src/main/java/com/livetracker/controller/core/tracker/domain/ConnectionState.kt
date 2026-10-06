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
    FAILED,
    DISCONNECTED
}
