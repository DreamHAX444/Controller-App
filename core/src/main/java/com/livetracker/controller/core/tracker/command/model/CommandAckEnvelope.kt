package com.livetracker.controller.core.tracker.command.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Shared protocol envelope for Realtime command execution acknowledgements.
 * Broadcast on the "command_ack" event of "public:commands" channel.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandAckEnvelope(
    @SerialName("idempotency_key")
    @JsonNames("idempotencyKey", "idempotency_key")
    val idempotencyKey: String,
    @SerialName("command_id")
    @JsonNames("commandId", "command_id")
    val commandId: String? = null,
    val status: String, // "COMPLETED" | "FAILED"
    @SerialName("result_payload")
    @JsonNames("resultPayload", "result_payload")
    val resultPayload: String? = null,
    @SerialName("error_message")
    @JsonNames("errorMessage", "error_message")
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("sequence_id")
    @JsonNames("sequenceId", "sequence_id")
    val sequenceId: Long? = null
)
