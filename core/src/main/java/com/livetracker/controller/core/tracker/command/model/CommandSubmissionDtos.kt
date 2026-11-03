package com.livetracker.controller.core.tracker.command.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandSubmissionRequest(
    @SerialName("device_id")
    val deviceId: String,
    val command: String,
    val params: JsonObject? = null,
    @SerialName("idempotency_key")
    val idempotencyKey: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandSubmissionResponse(
    val id: Long,
    @JsonNames("device_id", "deviceId")
    val deviceId: String,
    val command: String,
    val params: JsonObject? = null,
    @JsonNames("idempotency_key", "idempotencyKey")
    val idempotencyKey: String,
    val status: String,
    @JsonNames("key_id", "keyId")
    val keyId: String,
    @JsonNames("server_signature", "serverSignature")
    val serverSignature: String,
    @JsonNames("issued_at_epoch_sec", "issuedAtEpochSec")
    val issuedAtEpochSec: Long,
    @JsonNames("expires_at_epoch_sec", "expiresAtEpochSec")
    val expiresAtEpochSec: Long,
    @JsonNames("created_at", "createdAt")
    val createdAt: String? = null
)
