package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultSignalingSession(
    override val sessionId: String,
    override val trackerId: TrackerId,
    private val transport: SignalingTransport,
    private val scope: CoroutineScope,
    private val timeoutPolicy: SignalingTimeoutPolicy,
    private val onClosed: () -> Unit
) : SignalingSession {

    private val _state = MutableStateFlow(SignalingState.DISCONNECTED)
    override val state: StateFlow<SignalingState> = _state.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    private val _lastError = MutableStateFlow<SignalingError?>(null)
    override val lastError: StateFlow<SignalingError?> = _lastError.asStateFlow()

    private val mutex = Mutex()
    private var job: Job? = null
    private val processedMessageIds = mutableSetOf<String>()

    override suspend fun start() {
        mutex.withLock {
            if (_state.value != SignalingState.DISCONNECTED && _state.value != SignalingState.FAILED) {
                return
            }
            _state.value = SignalingState.CONNECTING
            try {
                transport.connect(sessionId)
                _state.value = SignalingState.CONNECTED
                
                job = scope.launch {
                    try {
                        transport.receiveMessages(sessionId).collect { msg ->
                            handleIncomingMessage(msg)
                        }
                    } catch (e: Exception) {
                        fail(SignalingError.TransportError("Receive failed", e))
                    }
                }
            } catch (e: Exception) {
                fail(SignalingError.TransportError("Connection failed", e))
            }
        }
    }

    private suspend fun handleIncomingMessage(msg: SignalingMessage) {
        if (msg.sessionId != sessionId || msg.trackerId != trackerId) {
            return
        }

        if (!processedMessageIds.add(msg.messageId)) {
            return // duplicate
        }
        if (processedMessageIds.size > 100) {
            val iter = processedMessageIds.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }

        when (msg.type) {
            SignalingMessageType.READY -> {
                if (_state.value == SignalingState.CONNECTED || _state.value == SignalingState.NEGOTIATING) {
                    _state.value = SignalingState.READY
                }
            }
            SignalingMessageType.SESSION_ENDED -> {
                internalStop()
            }
            SignalingMessageType.ERROR -> {
                val errMsg = (msg.payload as? ErrorPayload)?.message ?: "Unknown error from remote"
                fail(SignalingError.InvalidMessage("Remote error: $errMsg"))
            }
            SignalingMessageType.OFFER, SignalingMessageType.ANSWER -> {
                if (_state.value == SignalingState.READY || _state.value == SignalingState.CONNECTED) {
                    _state.value = SignalingState.NEGOTIATING
                }
            }
            else -> {}
        }
        _incomingMessages.tryEmit(msg)
    }

    override suspend fun sendMessage(type: SignalingMessageType, payload: SignalingPayload?) {
        val currentState = _state.value
        if (currentState == SignalingState.DISCONNECTED || currentState == SignalingState.CLOSING || currentState == SignalingState.FAILED) {
            throw SignalingError.SessionClosed("Cannot send message in state $currentState")
        }

        val msg = SignalingMessage(
            sessionId = sessionId,
            trackerId = trackerId,
            type = type,
            timestamp = System.currentTimeMillis(),
            messageId = UUID.randomUUID().toString(),
            payload = payload
        )

        try {
            transport.sendMessage(msg)
        } catch (e: Exception) {
            fail(SignalingError.TransportError("Send failed", e))
            throw e
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            if (_state.value == SignalingState.CLOSING || _state.value == SignalingState.DISCONNECTED) {
                return
            }
            _state.value = SignalingState.CLOSING
            try {
                val msg = SignalingMessage(
                    sessionId = sessionId,
                    trackerId = trackerId,
                    type = SignalingMessageType.STOP_REQUEST,
                    timestamp = System.currentTimeMillis(),
                    messageId = UUID.randomUUID().toString(),
                    payload = null
                )
                transport.sendMessage(msg)
                transport.disconnect(sessionId)
            } catch (e: Exception) {
                // Ignore disconnect errors
            } finally {
                _state.value = SignalingState.DISCONNECTED
                job?.cancel()
                onClosed()
            }
        }
    }

    internal fun internalStop() {
        if (_state.value == SignalingState.DISCONNECTED || _state.value == SignalingState.CLOSING) return
        _state.value = SignalingState.DISCONNECTED
        scope.launch {
            try { transport.disconnect(sessionId) } catch (e: Exception) {}
        }
        job?.cancel()
        onClosed()
    }

    private fun fail(error: SignalingError) {
        _lastError.value = error
        _state.value = SignalingState.FAILED
        job?.cancel()
        scope.launch {
            try { transport.disconnect(sessionId) } catch (e: Exception) {}
        }
    }
}
