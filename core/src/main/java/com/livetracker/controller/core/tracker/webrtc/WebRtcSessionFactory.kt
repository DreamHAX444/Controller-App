package com.livetracker.controller.core.tracker.webrtc

import com.livetracker.controller.core.tracker.domain.TrackerId

interface WebRtcSessionFactory {
    fun createSession(
        trackerId: TrackerId,
        sessionId: String,
        configuration: WebRtcConfiguration
    ): WebRtcSession
}
