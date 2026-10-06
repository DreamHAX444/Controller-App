package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class SignalingMessageType {
    START_REQUEST,
    READY,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    STOP_REQUEST,
    SESSION_ENDED,
    ERROR,
    VIDEO_PROFILE,
    CAMERA_TELEMETRY
}

sealed interface SignalingPayload

data class ReadyPayload(val generation: Long) : SignalingPayload
data class OfferPayload(val sdp: String, val generation: Long, val offerId: String = "") : SignalingPayload
data class AnswerPayload(val sdp: String, val generation: Long, val answerId: String = "") : SignalingPayload
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val generation: Long,
    val candidateId: String = ""
) : SignalingPayload
data class ErrorPayload(val message: String) : SignalingPayload

data class SignalingMessage(
    val sessionId: String,
    val trackerId: TrackerId,
    val type: SignalingMessageType,
    val timestamp: Long,
    val messageId: String,
    val payload: SignalingPayload? = null
)
