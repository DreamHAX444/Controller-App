package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.data.model.ManagedUserRecord
import com.livetracker.controller.core.auth.data.model.UserFeaturePermissionRecord
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord

/**
 * Repository interface for Controller User Management & Access Control (TRACE V1 Phase 5).
 * Governs user lifecycle approval, role administration, device assignments,
 * and granular feature permissions.
 */
interface UserManagementRepository {

    /**
     * Fetches managed user records, optionally filtered by role, account status,
     * or a text search query matching display name, email, or user ID.
     */
    suspend fun fetchUsers(
        roleFilter: Role? = null,
        statusFilter: AccountStatus? = null,
        query: String? = null
    ): Result<List<ManagedUserRecord>>

    /**
     * Approves a pending user, setting their status to [AccountStatus.ACTIVE].
     * If the user's current role is [Role.VISITOR], promotes their role to [Role.USER].
     */
    suspend fun approveUser(userId: String): Result<ManagedUserRecord>

    /**
     * Updates a user's account lifecycle status (e.g. ACTIVE, SUSPENDED, DISABLED, PENDING).
     */
    suspend fun updateUserStatus(userId: String, newStatus: AccountStatus): Result<ManagedUserRecord>

    /**
     * Updates a user's administrative role (OWNER, ADMIN, USER, VISITOR).
     */
    suspend fun updateUserRole(userId: String, newRole: Role): Result<ManagedUserRecord>

    /**
     * Fetches active device assignments for a specific user.
     */
    suspend fun fetchUserAssignments(userId: String): Result<List<DeviceAssignmentRecord>>

    /**
     * Fetches granted feature permissions for a specific user and device.
     */
    suspend fun fetchUserPermissions(userId: String, deviceId: String): Result<List<UserFeaturePermissionRecord>>

    /**
     * Grants a specific feature permission on a device for a user.
     */
    suspend fun grantFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String,
        expiresAt: String? = null
    ): Result<UserFeaturePermissionRecord>

    /**
     * Revokes a specific feature permission on a device for a user.
     */
    suspend fun revokeFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String
    ): Result<Unit>
}
