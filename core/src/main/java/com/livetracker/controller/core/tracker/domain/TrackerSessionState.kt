package com.livetracker.controller.core.tracker.domain

import com.livetracker.controller.core.auth.domain.ServicePermission

data class TrackerSessionState(
    val deviceId: TrackerId,
    val displayName: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val signalingState: String = "CLOSED",
    val webrtcState: String = "CLOSED",
    val heartbeatState: HeartbeatState = HeartbeatState(),
    val capabilities: TrackerCapabilities = TrackerCapabilities(),
    val permissions: Set<ServicePermission> = emptySet(),
    val lastEventTimestamp: Long? = null,
    val lastError: SessionError? = null,
    val sessionId: String? = null
)
