package com.livetracker.controller.core.tracker.webrtc

data class WebRtcConfiguration(
    val iceServers: List<IceServer> = listOf(
        IceServer("stun:stun.l.google.com:19302")
    )
)

data class IceServer(
    val url: String,
    val username: String? = null,
    val password: String? = null
)
