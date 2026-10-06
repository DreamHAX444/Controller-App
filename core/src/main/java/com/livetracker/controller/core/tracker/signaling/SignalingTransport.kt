package com.livetracker.controller.core.tracker.signaling

import kotlinx.coroutines.flow.Flow

interface SignalingTransport {
    suspend fun connect(sessionId: String)
    suspend fun disconnect(sessionId: String)
    fun receiveMessages(sessionId: String): Flow<SignalingMessage>
    suspend fun sendMessage(message: SignalingMessage)
}
