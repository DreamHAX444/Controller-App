package com.livetracker.controller.core.tracker.webrtc

import com.livetracker.controller.core.tracker.domain.TrackerId

interface WebRtcSessionManager {
    fun getSession(trackerId: TrackerId): WebRtcSession?
    fun createSession(trackerId: TrackerId, sessionId: String, configuration: WebRtcConfiguration): WebRtcSession
    fun removeSession(trackerId: TrackerId)
    fun getAllSessions(): Map<TrackerId, WebRtcSession>
}
