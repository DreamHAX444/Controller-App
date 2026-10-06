package com.livetracker.controller.core.tracker.domain

sealed interface CommandResult {
    val commandId: String
    val deviceId: TrackerId

    data class Accepted(override val commandId: String, override val deviceId: TrackerId) : CommandResult
    data class Rejected(override val commandId: String, override val deviceId: TrackerId, val reason: String) : CommandResult
    data class Unauthorized(override val commandId: String, override val deviceId: TrackerId) : CommandResult
    data class Unsupported(override val commandId: String, override val deviceId: TrackerId) : CommandResult
    data class Timeout(override val commandId: String, override val deviceId: TrackerId) : CommandResult
    data class Failed(override val commandId: String, override val deviceId: TrackerId, val reason: String) : CommandResult
    data class Completed(override val commandId: String, override val deviceId: TrackerId, val payload: String? = null) : CommandResult
}
