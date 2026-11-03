package com.livetracker.controller.core.tracker.audio.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class AudioStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    STOPPED,
    ERROR
}

data class AudioState(
    val deviceId: TrackerId,
    val sessionId: String? = null,
    val status: AudioStatus = AudioStatus.IDLE,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val amplitude: Float = 0f,
    val waveformBars: List<Float> = emptyList()
) {
    val isStreaming: Boolean
        get() = status == AudioStatus.STREAMING
}
