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
        return sessions.getOrPut(trackerId) {
            factory.createSession(trackerId, sessionId, configuration)
        }
    }

    override fun removeSession(trackerId: TrackerId) {
        sessions.remove(trackerId)
    }

    override fun getAllSessions(): Map<TrackerId, WebRtcSession> {
        return sessions.toMap()
    }
}
