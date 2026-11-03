package com.livetracker.controller.core.auth.data.model

import com.livetracker.controller.core.auth.domain.FeaturePermissionMapper
import com.livetracker.controller.core.auth.domain.ServicePermission
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer and Domain representation of a granular device feature permission
 * from public.device_feature_permissions (TRACE V1 Phase 5).
 *
 * @property id Primary key of the permission record.
 * @property assignmentId Reference to public.user_device_assignments(id).
 * @property deviceId The associated tracker device ID.
 * @property feature Database feature string ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS').
 * @property isAllowed Whether the permission is granted (true) or revoked (false).
 * @property grantedAt ISO 8601 timestamp when granted.
 * @property expiresAt Optional ISO 8601 timestamp when the permission expires.
 */
@Serializable
data class UserFeaturePermissionRecord(
    val id: Long? = null,
    @SerialName("assignment_id")
    val assignmentId: Long,
    @SerialName("device_id")
    val deviceId: String = "",
    val feature: String,
    @SerialName("is_allowed")
    val isAllowed: Boolean = true,
    @SerialName("granted_at")
    val grantedAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null
) {
    /**
     * Unfolds the database feature string into corresponding domain [ServicePermission] values.
     */
    val servicePermissions: Set<ServicePermission>
        get() = FeaturePermissionMapper.toServicePermissions(feature)
}
