package com.livetracker.controller.core.tracker.webrtc.datachannel

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TrackerCommandEnvelope(
    val id: Long? = null,
    @SerialName("device_id")
    @JsonNames("device_id", "deviceId")
    val deviceId: String,
    val command: String,
    val params: JsonElement? = null,
    val status: String? = null,
    @SerialName("idempotency_key")
    @JsonNames("idempotency_key", "idempotencyKey")
    val idempotencyKey: String? = null
) {
    /**
     * Backwards-compatible secondary constructor accepting string parameters.
     */
    constructor(
        id: Long? = null,
        deviceId: String,
        command: String,
        params: String?,
        status: String? = null,
        idempotencyKey: String? = null
    ) : this(
        id = id,
        deviceId = deviceId,
        command = command,
        params = params?.let {
            try {
                Json.parseToJsonElement(it)
            } catch (_: Exception) {
                JsonPrimitive(it)
            }
        },
        status = status,
        idempotencyKey = idempotencyKey
    )

    /**
     * Resolves the effective correlation key for response matching:
     * Prefers numeric `id.toString()` if present, else falls back to `idempotencyKey`.
     */
    val effectiveCorrelationId: String?
        get() = id?.toString() ?: idempotencyKey

    /**
     * Extracts parameters as a String suitable for command dispatch handlers
     * expecting JSON string or raw string parameters.
     */
    fun getParamsAsString(): String? {
        val p = params ?: return null
        return when (p) {
            is JsonPrimitive -> if (p.isString) p.content else p.toString()
            is JsonNull -> null
            else -> p.toString()
        }
    }
}
