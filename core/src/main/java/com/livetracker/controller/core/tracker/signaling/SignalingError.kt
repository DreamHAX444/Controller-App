package com.livetracker.controller.core.tracker.signaling

sealed class SignalingError : Exception() {
    data class TransportError(override val message: String, val causeException: Throwable? = null) : SignalingError()
    data class InvalidMessage(override val message: String) : SignalingError()
    data class TimeoutError(override val message: String) : SignalingError()
    data class Unauthorized(override val message: String) : SignalingError()
    data class InvalidState(override val message: String) : SignalingError()
    data class SessionClosed(override val message: String) : SignalingError()
}
