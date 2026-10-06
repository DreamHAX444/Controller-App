package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId

interface SignalingManager {
    fun createSession(trackerId: TrackerId, sessionId: String): SignalingSession
    fun getSession(trackerId: TrackerId): SignalingSession?
    suspend fun closeSession(trackerId: TrackerId)
    fun closeAll()
}
