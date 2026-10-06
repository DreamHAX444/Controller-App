package com.livetracker.controller.core.tracker.domain

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    RECONNECTING
}
