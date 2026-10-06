package com.livetracker.controller.core.tracker.domain

sealed interface TrackerEvent {
    val eventId: String
    val deviceId: TrackerId
    val timestamp: Long

    data class LocationUpdated(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val bearing: Float? = null,
        val speed: Float? = null
    ) : TrackerEvent

    data class ConnectionChanged(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val state: ConnectionState
    ) : TrackerEvent

    data class HeartbeatReceived(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val sequence: Int
    ) : TrackerEvent

    data class CapabilitiesUpdated(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val capabilities: TrackerCapabilities
    ) : TrackerEvent

    data class ServiceStateChanged(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val service: String,
        val active: Boolean
    ) : TrackerEvent

    data class CommandResultEvent(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val commandId: String,
        val result: CommandResult
    ) : TrackerEvent

    data class Error(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        val error: SessionError,
        val message: String? = null
    ) : TrackerEvent

    data class SessionStarted(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long
    ) : TrackerEvent

    data class SessionEnded(
        override val eventId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long
    ) : TrackerEvent
}
