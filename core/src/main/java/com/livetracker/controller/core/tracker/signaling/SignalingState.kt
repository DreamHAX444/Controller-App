package com.livetracker.controller.core.tracker.signaling

enum class SignalingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NEGOTIATING,
    READY,
    CLOSING,
    FAILED
}
