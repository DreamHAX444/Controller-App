package com.livetracker.controller.core.tracker.signaling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeSignalingTransport : SignalingTransport {

    val connectedSessions = mutableSetOf<String>()
    val sentMessages = mutableListOf<SignalingMessage>()
    private val flows = mutableMapOf<String, MutableSharedFlow<SignalingMessage>>()

    var connectError: Throwable? = null
    var sendError: Throwable? = null

    override suspend fun connect(sessionId: String) {
        if (connectError != null) throw connectError!!
        connectedSessions.add(sessionId)
        if (!flows.containsKey(sessionId)) {
            flows[sessionId] = MutableSharedFlow(extraBufferCapacity = 64)
        }
    }

    override suspend fun disconnect(sessionId: String) {
        connectedSessions.remove(sessionId)
    }

    override fun receiveMessages(sessionId: String): Flow<SignalingMessage> {
        if (!flows.containsKey(sessionId)) {
            flows[sessionId] = MutableSharedFlow(extraBufferCapacity = 64)
        }
        return flows[sessionId]!!.asSharedFlow()
    }

    override suspend fun sendMessage(message: SignalingMessage) {
        if (sendError != null) throw sendError!!
        sentMessages.add(message)
    }

    fun simulateIncoming(message: SignalingMessage) {
        flows[message.sessionId]?.tryEmit(message)
    }
}
