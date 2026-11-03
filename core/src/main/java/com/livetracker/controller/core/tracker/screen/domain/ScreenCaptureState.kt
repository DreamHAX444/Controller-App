package com.livetracker.controller.core.tracker.screen.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class ScreenCaptureStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    AWAITING_CONSENT,
    STOPPED,
    ERROR
}

data class ScreenCaptureState(
    val deviceId: TrackerId,
    val sessionId: String? = null,
    val status: ScreenCaptureStatus = ScreenCaptureStatus.IDLE,
    val qualityProfile: String = "BALANCED",
    val errorMessage: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val isStreaming: Boolean
        get() = status == ScreenCaptureStatus.STREAMING
}
