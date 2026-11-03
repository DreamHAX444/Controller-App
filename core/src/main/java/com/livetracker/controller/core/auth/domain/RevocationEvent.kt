package com.livetracker.controller.core.auth.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable DTO representing a revocation event published by PostgreSQL triggers
 * or the broadcast_revocation_event RPC.
 */
@Serializable
data class RevocationEventRecord(
    val id: Long? = null,
    @SerialName("target_user_id")
    val targetUserId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("device_id")
    val deviceId: String? = null,
    val feature: String? = null,
    val reason: String? = null,
    @SerialName("revoked_by")
    val revokedBy: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)
