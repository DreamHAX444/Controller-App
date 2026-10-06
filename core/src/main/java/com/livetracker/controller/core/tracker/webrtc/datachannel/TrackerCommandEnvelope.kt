package com.livetracker.controller.core.tracker.webrtc.datachannel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackerCommandEnvelope(
    val id: Long? = null,
    @SerialName("device_id")
    val deviceId: String,
    val command: String,
    val params: String? = null,
    val status: String? = null
)
