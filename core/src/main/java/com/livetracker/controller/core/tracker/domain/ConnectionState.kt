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
