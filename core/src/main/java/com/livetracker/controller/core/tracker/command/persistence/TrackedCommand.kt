package com.livetracker.controller.core.tracker.command.persistence

import kotlinx.serialization.Serializable

@Serializable
data class TrackedCommand(
    val idempotencyKey: String,
    val commandId: Long? = null,
    val deviceId: String,
    val commandName: String,
    val paramsJson: String? = null,
    val status: CommandStatus = CommandStatus.PENDING,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochSec: Long? = null,
    val resultMessage: String? = null,
    val retryCount: Int = 0
)
