package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SignalingSession {
    val sessionId: String
    val trackerId: TrackerId
    val state: StateFlow<SignalingState>
    val incomingMessages: SharedFlow<SignalingMessage>
    val lastError: StateFlow<SignalingError?>

    suspend fun start()
    suspend fun stop()
    suspend fun sendMessage(type: SignalingMessageType, payload: SignalingPayload? = null)
}
