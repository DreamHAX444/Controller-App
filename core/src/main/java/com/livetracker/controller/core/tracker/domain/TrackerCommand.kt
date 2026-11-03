package com.livetracker.controller.core.tracker.domain

import java.util.UUID

sealed interface TrackerCommand {
    val commandId: String
    val deviceId: TrackerId
    val timestamp: Long
    val actorId: String
    val timeoutMs: Long
    val idempotencyKey: String
        get() = commandId
    val sequenceId: Long?
        get() = commandId.toLongOrNull()

    data class RequestLocation(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 5000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class GetCameraCapabilities(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StartCamera(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        val cameraId: String? = null,
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class SwitchCamera(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        val cameraId: String? = null,
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StopCamera(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 5000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StartAudio(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        val sessionId: String = commandId,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StopAudio(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 5000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StartScreen(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        val sessionId: String = commandId,
        val qualityProfile: String = "BALANCED",
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class StopScreen(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 5000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class ListFiles(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 15000,
        val path: String = "/",
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class DownloadFile(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 30000,
        val path: String,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class DownloadBatch(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 60000,
        val paths: List<String>,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class UploadFile(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 30000,
        val targetDirectory: String,
        val fileName: String,
        val dataBase64: String,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class DeleteFile(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        val path: String,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class Wake(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand

    data class Sleep(
        override val commandId: String = UUID.randomUUID().toString(),
        override val deviceId: TrackerId,
        override val timestamp: Long = System.currentTimeMillis(),
        override val actorId: String = "controller",
        override val timeoutMs: Long = 10000,
        override val idempotencyKey: String = commandId,
        override val sequenceId: Long? = commandId.toLongOrNull()
    ) : TrackerCommand
}
