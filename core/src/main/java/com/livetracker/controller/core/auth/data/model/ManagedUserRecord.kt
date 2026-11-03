package com.livetracker.controller.core.auth.data.model

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer and Domain representation for a managed user profile in
 * Controller User Management (TRACE V1 Phase 5).
 *
 * @property id Unique user ID (Supabase Auth UID).
 * @property email User email address if present.
 * @property displayName User display name.
 * @property role Role enum (OWNER, ADMIN, USER, VISITOR).
 * @property status Account lifecycle status (PENDING, ACTIVE, DISABLED, SUSPENDED, UNKNOWN).
 * @property createdAt ISO 8601 creation timestamp string.
 * @property updatedAt ISO 8601 last update timestamp string.
 * @property assignedDeviceCount Total number of active hardware device assignments.
 * @property pendingApproval True if the account is awaiting administrator approval (status == PENDING).
 */
@Serializable
data class ManagedUserRecord(
    val id: String,
    val email: String? = null,
    @SerialName("display_name")
    val displayName: String = "",
    val role: Role = Role.VISITOR,
    val status: AccountStatus = AccountStatus.PENDING,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("assigned_device_count")
    val assignedDeviceCount: Int = 0,
    @SerialName("pending_approval")
    val pendingApproval: Boolean = (status == AccountStatus.PENDING)
)
