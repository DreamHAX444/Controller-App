package com.livetracker.controller.core.auth.data.model

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data representation of public.profiles returned by Supabase backend.
 * Uses safe String backing properties to guarantee fail-closed parsing.
 */
@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("role")
    val roleStr: String = "VISITOR",
    @SerialName("status")
    val statusStr: String = "ACTIVE",
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val role: Role
        get() = try {
            Role.valueOf(roleStr.trim().uppercase())
        } catch (_: Exception) {
            Role.VISITOR
        }

    val status: AccountStatus
        get() = try {
            AccountStatus.valueOf(statusStr.trim().uppercase())
        } catch (_: Exception) {
            AccountStatus.UNKNOWN
        }
}

@Serializable
data class ProfileUpsert(
    val id: String,
    val email: String,
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val role: String = "VISITOR",
    val status: String = "ACTIVE"
)
