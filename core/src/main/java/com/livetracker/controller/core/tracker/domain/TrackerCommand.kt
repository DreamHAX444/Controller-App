package com.livetracker.controller.core.tracker.domain

sealed interface TrackerCommand {
    val commandId: String
    val deviceId: TrackerId
    val timestamp: Long
    val actorId: String
    val timeoutMs: Long

    data class RequestLocation(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 5000
    ) : TrackerCommand

    data class StartCamera(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 10000,
        val cameraId: String? = null
    ) : TrackerCommand

    data class StopCamera(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 5000
    ) : TrackerCommand

    data class StartAudio(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 10000
    ) : TrackerCommand

    data class StopAudio(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 5000
    ) : TrackerCommand

    data class StartScreen(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 10000
    ) : TrackerCommand

    data class StopScreen(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 5000
    ) : TrackerCommand

    data class ListFiles(
        override val commandId: String,
        override val deviceId: TrackerId,
        override val timestamp: Long,
        override val actorId: String,
        override val timeoutMs: Long = 15000,
        val path: String = "/"
    ) : TrackerCommand
}
