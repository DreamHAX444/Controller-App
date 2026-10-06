package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultSignalingManager(
    private val transport: SignalingTransport,
    private val scope: CoroutineScope,
    private val timeoutPolicy: SignalingTimeoutPolicy = SignalingTimeoutPolicy()
) : SignalingManager {

    private val sessions = mutableMapOf<TrackerId, DefaultSignalingSession>()
    private val mutex = Mutex()
    private var observeJob: Job? = null

    init {
        startObserving()
    }

    private fun startObserving() {
        observeJob = scope.launch {
            // Note: If transport can only observe per session, we would observe inside the session.
            // But if it's a global transport, it would expose a global flow.
            // Since our SignalingTransport interface has `receiveMessages(sessionId)`, 
            // the transport handles per-session streams. But wait, if the transport uses a single WebSocket, 
            // how does it receive messages for sessions? It receives all and filters.
            // Let's assume the session itself will call transport.receiveMessages(sessionId).
        }
    }

    override fun createSession(trackerId: TrackerId, sessionId: String): SignalingSession {
        // We need runBlocking or just lock in a synchronized block because of mutable map?
        // Let's use concurrent map or just sync block.
        synchronized(sessions) {
            sessions[trackerId]?.let { existing ->
                if (existing.sessionId == sessionId) {
                    return existing
                }
                // Stop old session
                existing.internalStop()
            }
            val session = DefaultSignalingSession(
                sessionId = sessionId,
                trackerId = trackerId,
                transport = transport,
                scope = scope,
                timeoutPolicy = timeoutPolicy,
                onClosed = { removeSession(trackerId, sessionId) }
            )
            sessions[trackerId] = session
            return session
        }
    }

    override fun getSession(trackerId: TrackerId): SignalingSession? {
        synchronized(sessions) {
            return sessions[trackerId]
        }
    }

    override suspend fun closeSession(trackerId: TrackerId) {
        val session = synchronized(sessions) { sessions.remove(trackerId) }
        session?.stop()
    }

    override fun closeAll() {
        val all = synchronized(sessions) {
            val list = sessions.values.toList()
            sessions.clear()
            list
        }
        all.forEach { it.internalStop() }
        observeJob?.cancel()
    }

    private fun removeSession(trackerId: TrackerId, sessionId: String) {
        synchronized(sessions) {
            val current = sessions[trackerId]
            if (current?.sessionId == sessionId) {
                sessions.remove(trackerId)
            }
        }
    }
}
