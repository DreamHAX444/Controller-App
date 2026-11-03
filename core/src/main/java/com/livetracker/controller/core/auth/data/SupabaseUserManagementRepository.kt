package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.data.model.ManagedUserRecord
import com.livetracker.controller.core.auth.data.model.UserFeaturePermissionRecord
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.FeaturePermissionMapper
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.UserManagementRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Production Supabase implementation of [UserManagementRepository] for TRACE V1 Phase 5.
 * Interacts with public.profiles, public.user_device_assignments, and
 * public.device_feature_permissions via Supabase PostgREST.
 */
class SupabaseUserManagementRepository(
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.client },
    private val sessionProvider: (() -> Session?)? = null
) : UserManagementRepository {

    private fun checkAdminAuthorization(): SecurityException? {
        if (sessionProvider != null) {
            val session = sessionProvider.invoke()
            if (session == null || !session.isValid() || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                return SecurityException("Only Owner or Admin can perform this user management operation")
            }
        }
        return null
    }

    override suspend fun fetchUsers(
        roleFilter: Role?,
        statusFilter: AccountStatus?,
        query: String?
    ): Result<List<ManagedUserRecord>> = withContext(Dispatchers.IO) {
        try {
            val client = clientProvider()
            val profiles = client.from("profiles")
                .select {
                    filter {
                        if (roleFilter != null) {
                            eq("role", roleFilter.name)
                        }
                        if (statusFilter != null) {
                            eq("status", statusFilter.name)
                        }
                    }
                }
                .decodeList<ProfileRow>()

            val assignments = runCatching {
                client.from("user_device_assignments")
                    .select {
                        filter {
                            eq("status", "ACTIVE")
                        }
                    }
                    .decodeList<DeviceAssignmentRecord>()
            }.getOrDefault(emptyList())

            val assignmentCounts = assignments.groupingBy { it.userId }.eachCount()

            val records = profiles.map { profile ->
                profile.toManagedUserRecord(
                    assignedDeviceCount = assignmentCounts[profile.id] ?: 0
                )
            }

            val filtered = if (!query.isNullOrBlank()) {
                val q = query.trim().lowercase()
                records.filter { user ->
                    user.displayName.lowercase().contains(q) ||
                    (user.email?.lowercase()?.contains(q) == true) ||
                    user.id.lowercase().contains(q)
                }
            } else {
                records
            }

            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun approveUser(userId: String): Result<ManagedUserRecord> = withContext(Dispatchers.IO) {
        checkAdminAuthorization()?.let { return@withContext Result.failure(it) }
        try {
            val client = clientProvider()
            val existing = client.from("profiles")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingle<ProfileRow>()

            val upgradedRole = if (existing.parsedRole == Role.VISITOR) Role.USER else existing.parsedRole

            client.from("profiles").update(
                ProfileApprovalUpdatePayload(
                    status = AccountStatus.ACTIVE.name,
                    role = upgradedRole.name,
                    updatedAt = java.time.Instant.now().toString()
                )
            ) {
                filter { eq("id", userId) }
            }

            val assignmentCount = fetchUserAssignments(userId).getOrDefault(emptyList()).size
            val updated = existing.copy(
                status = AccountStatus.ACTIVE.name,
                role = upgradedRole.name
            ).toManagedUserRecord(assignedDeviceCount = assignmentCount)

            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserStatus(
        userId: String,
        newStatus: AccountStatus
    ): Result<ManagedUserRecord> = withContext(Dispatchers.IO) {
        checkAdminAuthorization()?.let { return@withContext Result.failure(it) }
        try {
            val client = clientProvider()
            client.from("profiles").update(
                ProfileStatusUpdatePayload(
                    status = newStatus.name,
                    updatedAt = java.time.Instant.now().toString()
                )
            ) {
                filter { eq("id", userId) }
            }

            val profile = client.from("profiles")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingle<ProfileRow>()

            val assignmentCount = fetchUserAssignments(userId).getOrDefault(emptyList()).size
            Result.success(profile.toManagedUserRecord(assignedDeviceCount = assignmentCount))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateUserRole(
        userId: String,
        newRole: Role
    ): Result<ManagedUserRecord> = withContext(Dispatchers.IO) {
        checkAdminAuthorization()?.let { return@withContext Result.failure(it) }
        try {
            val client = clientProvider()
            client.from("profiles").update(
                ProfileRoleUpdatePayload(
                    role = newRole.name,
                    updatedAt = java.time.Instant.now().toString()
                )
            ) {
                filter { eq("id", userId) }
            }

            val profile = client.from("profiles")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingle<ProfileRow>()

            val assignmentCount = fetchUserAssignments(userId).getOrDefault(emptyList()).size
            Result.success(profile.toManagedUserRecord(assignedDeviceCount = assignmentCount))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchUserAssignments(userId: String): Result<List<DeviceAssignmentRecord>> = withContext(Dispatchers.IO) {
        try {
            val client = clientProvider()
            val assignments = client.from("user_device_assignments")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("status", "ACTIVE")
                    }
                }
                .decodeList<DeviceAssignmentRecord>()
            Result.success(assignments)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchUserPermissions(
        userId: String,
        deviceId: String
    ): Result<List<UserFeaturePermissionRecord>> = withContext(Dispatchers.IO) {
        try {
            val client = clientProvider()
            val assignments = client.from("user_device_assignments")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("device_id", deviceId)
                        eq("status", "ACTIVE")
                    }
                }
                .decodeList<DeviceAssignmentRecord>()

            val assignment = assignments.firstOrNull()
                ?: return@withContext Result.success(emptyList())

            val assignmentId = assignment.id
                ?: return@withContext Result.success(emptyList())

            val permissions = client.from("device_feature_permissions")
                .select {
                    filter {
                        eq("assignment_id", assignmentId)
                    }
                }
                .decodeList<DeviceFeaturePermissionRow>()

            val mapped = permissions.map { row ->
                UserFeaturePermissionRecord(
                    id = row.id,
                    assignmentId = row.assignmentId,
                    deviceId = deviceId,
                    feature = row.feature,
                    isAllowed = row.isAllowed,
                    grantedAt = row.grantedAt,
                    expiresAt = row.expiresAt
                )
            }
            Result.success(mapped)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun grantFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String,
        expiresAt: String?
    ): Result<UserFeaturePermissionRecord> = withContext(Dispatchers.IO) {
        checkAdminAuthorization()?.let { return@withContext Result.failure(it) }
        try {
            val cleanFeature = feature.trim().uppercase()
            if (!FeaturePermissionMapper.isValidDatabaseFeature(cleanFeature)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid feature: '$feature'. Supported features: ${FeaturePermissionMapper.ALL_DATABASE_FEATURES}")
                )
            }

            val client = clientProvider()
            val assignments = client.from("user_device_assignments")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("device_id", deviceId)
                        eq("status", "ACTIVE")
                    }
                }
                .decodeList<DeviceAssignmentRecord>()

            val assignment = assignments.firstOrNull()
                ?: return@withContext Result.failure(
                    NoSuchElementException("No active device assignment found for user '$userId' and device '$deviceId'")
                )

            val assignmentId = assignment.id
                ?: return@withContext Result.failure(IllegalStateException("Device assignment id cannot be null"))

            val upsertPayload = DeviceFeaturePermissionUpsertPayload(
                assignmentId = assignmentId,
                feature = cleanFeature,
                isAllowed = true,
                expiresAt = expiresAt
            )

            client.from("device_feature_permissions").upsert(upsertPayload)

            val row = client.from("device_feature_permissions")
                .select {
                    filter {
                        eq("assignment_id", assignmentId)
                        eq("feature", cleanFeature)
                    }
                }
                .decodeSingle<DeviceFeaturePermissionRow>()

            Result.success(
                UserFeaturePermissionRecord(
                    id = row.id,
                    assignmentId = row.assignmentId,
                    deviceId = deviceId,
                    feature = row.feature,
                    isAllowed = row.isAllowed,
                    grantedAt = row.grantedAt,
                    expiresAt = row.expiresAt
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokeFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        checkAdminAuthorization()?.let { return@withContext Result.failure(it) }
        try {
            val cleanFeature = feature.trim().uppercase()
            val client = clientProvider()
            val assignments = client.from("user_device_assignments")
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("device_id", deviceId)
                        eq("status", "ACTIVE")
                    }
                }
                .decodeList<DeviceAssignmentRecord>()

            val assignment = assignments.firstOrNull()
            if (assignment != null && assignment.id != null) {
                client.from("device_feature_permissions").update(RevokePermissionPayload(isAllowed = false)) {
                    filter {
                        eq("assignment_id", assignment.id)
                        eq("feature", cleanFeature)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Serializable
private data class ProfileRow(
    val id: String,
    val email: String? = null,
    @SerialName("display_name")
    val displayName: String = "",
    val role: String = "VISITOR",
    val status: String = "PENDING",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val parsedRole: Role
        get() = try {
            Role.valueOf(role.trim().uppercase())
        } catch (_: Exception) {
            Role.VISITOR
        }

    val parsedStatus: AccountStatus
        get() = try {
            AccountStatus.valueOf(status.trim().uppercase())
        } catch (_: Exception) {
            AccountStatus.UNKNOWN
        }

    fun toManagedUserRecord(assignedDeviceCount: Int = 0): ManagedUserRecord {
        return ManagedUserRecord(
            id = id,
            email = email,
            displayName = displayName.ifBlank { email ?: "User ${id.take(8)}" },
            role = parsedRole,
            status = parsedStatus,
            createdAt = createdAt,
            updatedAt = updatedAt,
            assignedDeviceCount = assignedDeviceCount,
            pendingApproval = (parsedStatus == AccountStatus.PENDING)
        )
    }
}

@Serializable
private data class ProfileStatusUpdatePayload(
    val status: String,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
private data class ProfileRoleUpdatePayload(
    val role: String,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
private data class ProfileApprovalUpdatePayload(
    val status: String,
    val role: String,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
private data class DeviceFeaturePermissionRow(
    val id: Long? = null,
    @SerialName("assignment_id")
    val assignmentId: Long,
    val feature: String,
    @SerialName("is_allowed")
    val isAllowed: Boolean = true,
    @SerialName("granted_at")
    val grantedAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("granted_by")
    val grantedBy: String? = null
)

@Serializable
private data class DeviceFeaturePermissionUpsertPayload(
    @SerialName("assignment_id")
    val assignmentId: Long,
    val feature: String,
    @SerialName("is_allowed")
    val isAllowed: Boolean = true,
    @SerialName("expires_at")
    val expiresAt: String? = null
)

@Serializable
private data class RevokePermissionPayload(
    @SerialName("is_allowed")
    val isAllowed: Boolean = false
)
