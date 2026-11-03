package com.livetracker.controller.core.tracker.webrtc

import com.livetracker.controller.core.tracker.domain.TrackerId
import java.util.concurrent.ConcurrentHashMap

class DefaultWebRtcSessionManager(
    private val factory: WebRtcSessionFactory
) : WebRtcSessionManager {
    private val sessions = ConcurrentHashMap<TrackerId, WebRtcSession>()

    override fun getSession(trackerId: TrackerId): WebRtcSession? {
        return sessions[trackerId]
    }

    override fun createSession(
        trackerId: TrackerId,
        sessionId: String,
        configuration: WebRtcConfiguration
    ): WebRtcSession {
        return sessions.compute(trackerId) { _, existing ->
            if (existing != null && existing.state.value != WebRtcState.CLOSED && existing.state.value != WebRtcState.FAILED) {
                existing
            } else {
                factory.createSession(trackerId, sessionId, configuration)
            }
        }!!
    }

    override fun removeSession(trackerId: TrackerId) {
        sessions.remove(trackerId)
    }

    override fun getAllSessions(): Map<TrackerId, WebRtcSession> {
        return sessions.toMap()
    }
}
