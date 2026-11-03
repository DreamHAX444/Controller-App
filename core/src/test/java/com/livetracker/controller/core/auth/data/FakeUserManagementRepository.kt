package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.data.model.ManagedUserRecord
import com.livetracker.controller.core.auth.data.model.UserFeaturePermissionRecord
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.FeaturePermissionMapper
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.repository.UserManagementRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory test double and standalone fake implementation of [UserManagementRepository]
 * for unit testing, previews, and offline scenarios without requiring live Supabase credentials.
 */
class FakeUserManagementRepository : UserManagementRepository {

    private val users = ConcurrentHashMap<String, ManagedUserRecord>()
    private val assignments = CopyOnWriteArrayList<DeviceAssignmentRecord>()
    private val permissions = CopyOnWriteArrayList<UserFeaturePermissionRecord>()
    private val idGenerator = AtomicLong(100L)

    @Volatile
    private var simulatedFailure: Throwable? = null

    init {
        seedDefaults()
    }

    fun seedDefaults() {
        users.clear()
        assignments.clear()
        permissions.clear()

        // 1. Seed Default Users
        val defaultUsers = listOf(
            ManagedUserRecord(
                id = "u0",
                email = "owner@trace.local",
                displayName = "Fleet Owner",
                role = Role.OWNER,
                status = AccountStatus.ACTIVE,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                assignedDeviceCount = 2,
                pendingApproval = false
            ),
            ManagedUserRecord(
                id = "u1",
                email = "admin@trace.local",
                displayName = "Fleet Admin",
                role = Role.ADMIN,
                status = AccountStatus.ACTIVE,
                createdAt = "2026-01-02T00:00:00Z",
                updatedAt = "2026-01-02T00:00:00Z",
                assignedDeviceCount = 1,
                pendingApproval = false
            ),
            ManagedUserRecord(
                id = "u2",
                email = "user@trace.local",
                displayName = "Standard Field User",
                role = Role.USER,
                status = AccountStatus.ACTIVE,
                createdAt = "2026-01-03T00:00:00Z",
                updatedAt = "2026-01-03T00:00:00Z",
                assignedDeviceCount = 1,
                pendingApproval = false
            ),
            ManagedUserRecord(
                id = "u5",
                email = "visitor@trace.local",
                displayName = "Guest Visitor",
                role = Role.VISITOR,
                status = AccountStatus.ACTIVE,
                createdAt = "2026-01-05T00:00:00Z",
                updatedAt = "2026-01-05T00:00:00Z",
                assignedDeviceCount = 0,
                pendingApproval = false
            ),
            ManagedUserRecord(
                id = "u6",
                email = "pending@trace.local",
                displayName = "Pending Applicant",
                role = Role.VISITOR,
                status = AccountStatus.PENDING,
                createdAt = "2026-01-06T00:00:00Z",
                updatedAt = "2026-01-06T00:00:00Z",
                assignedDeviceCount = 0,
                pendingApproval = true
            ),
            ManagedUserRecord(
                id = "u3",
                email = "disabled@trace.local",
                displayName = "Disabled User",
                role = Role.USER,
                status = AccountStatus.DISABLED,
                createdAt = "2026-01-04T00:00:00Z",
                updatedAt = "2026-01-04T00:00:00Z",
                assignedDeviceCount = 0,
                pendingApproval = false
            ),
            ManagedUserRecord(
                id = "u4",
                email = "suspended@trace.local",
                displayName = "Suspended User",
                role = Role.USER,
                status = AccountStatus.SUSPENDED,
                createdAt = "2026-01-04T12:00:00Z",
                updatedAt = "2026-01-04T12:00:00Z",
                assignedDeviceCount = 0,
                pendingApproval = false
            )
        )

        defaultUsers.forEach { users[it.id] = it }

        // 2. Seed Default Assignments
        assignments.addAll(
            listOf(
                DeviceAssignmentRecord(id = 1L, userId = "u2", deviceId = "mock-tracker-1", status = "ACTIVE"),
                DeviceAssignmentRecord(id = 2L, userId = "u0", deviceId = "mock-tracker-1", status = "ACTIVE"),
                DeviceAssignmentRecord(id = 3L, userId = "u0", deviceId = "mock-tracker-2", status = "ACTIVE"),
                DeviceAssignmentRecord(id = 4L, userId = "u1", deviceId = "mock-tracker-1", status = "ACTIVE")
            )
        )

        // 3. Seed Default Permissions for User u2 on mock-tracker-1
        permissions.addAll(
            listOf(
                UserFeaturePermissionRecord(id = 10L, assignmentId = 1L, deviceId = "mock-tracker-1", feature = "LOCATION", isAllowed = true),
                UserFeaturePermissionRecord(id = 11L, assignmentId = 1L, deviceId = "mock-tracker-1", feature = "CAMERA", isAllowed = true),
                UserFeaturePermissionRecord(id = 12L, assignmentId = 1L, deviceId = "mock-tracker-1", feature = "MIC", isAllowed = true),
                UserFeaturePermissionRecord(id = 13L, assignmentId = 1L, deviceId = "mock-tracker-1", feature = "SCREEN", isAllowed = true),
                UserFeaturePermissionRecord(id = 14L, assignmentId = 1L, deviceId = "mock-tracker-1", feature = "FILES", isAllowed = true)
            )
        )
    }

    fun setSimulatedFailure(failure: Throwable?) {
        this.simulatedFailure = failure
    }

    fun addUser(user: ManagedUserRecord) {
        users[user.id] = user
    }

    fun addAssignment(assignment: DeviceAssignmentRecord) {
        assignments.add(assignment)
        val user = users[assignment.userId]
        if (user != null) {
            val count = assignments.count { it.userId == assignment.userId && it.status == "ACTIVE" }
            users[assignment.userId] = user.copy(assignedDeviceCount = count)
        }
    }

    override suspend fun fetchUsers(
        roleFilter: Role?,
        statusFilter: AccountStatus?,
        query: String?
    ): Result<List<ManagedUserRecord>> {
        simulatedFailure?.let { return Result.failure(it) }

        var list = users.values.toList()
        if (roleFilter != null) {
            list = list.filter { it.role == roleFilter }
        }
        if (statusFilter != null) {
            list = list.filter { it.status == statusFilter }
        }
        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { user ->
                user.displayName.lowercase().contains(q) ||
                (user.email?.lowercase()?.contains(q) == true) ||
                user.id.lowercase().contains(q)
            }
        }
        return Result.success(list)
    }

    override suspend fun approveUser(userId: String): Result<ManagedUserRecord> {
        simulatedFailure?.let { return Result.failure(it) }

        val existing = users[userId]
            ?: return Result.failure(NoSuchElementException("User with ID '$userId' not found"))

        val newRole = if (existing.role == Role.VISITOR) Role.USER else existing.role
        val updated = existing.copy(
            status = AccountStatus.ACTIVE,
            role = newRole,
            pendingApproval = false,
            updatedAt = java.time.Instant.now().toString()
        )
        users[userId] = updated
        return Result.success(updated)
    }

    override suspend fun updateUserStatus(
        userId: String,
        newStatus: AccountStatus
    ): Result<ManagedUserRecord> {
        simulatedFailure?.let { return Result.failure(it) }

        val existing = users[userId]
            ?: return Result.failure(NoSuchElementException("User with ID '$userId' not found"))

        val updated = existing.copy(
            status = newStatus,
            pendingApproval = (newStatus == AccountStatus.PENDING),
            updatedAt = java.time.Instant.now().toString()
        )
        users[userId] = updated
        return Result.success(updated)
    }

    override suspend fun updateUserRole(
        userId: String,
        newRole: Role
    ): Result<ManagedUserRecord> {
        simulatedFailure?.let { return Result.failure(it) }

        val existing = users[userId]
            ?: return Result.failure(NoSuchElementException("User with ID '$userId' not found"))

        val updated = existing.copy(
            role = newRole,
            updatedAt = java.time.Instant.now().toString()
        )
        users[userId] = updated
        return Result.success(updated)
    }

    override suspend fun fetchUserAssignments(userId: String): Result<List<DeviceAssignmentRecord>> {
        simulatedFailure?.let { return Result.failure(it) }

        val userAssignments = assignments.filter { it.userId == userId && it.status == "ACTIVE" }
        return Result.success(userAssignments)
    }

    override suspend fun fetchUserPermissions(
        userId: String,
        deviceId: String
    ): Result<List<UserFeaturePermissionRecord>> {
        simulatedFailure?.let { return Result.failure(it) }

        val assignment = assignments.firstOrNull { it.userId == userId && it.deviceId == deviceId && it.status == "ACTIVE" }
            ?: return Result.success(emptyList())

        val userPerms = permissions.filter { it.assignmentId == assignment.id }
        return Result.success(userPerms)
    }

    override suspend fun grantFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String,
        expiresAt: String?
    ): Result<UserFeaturePermissionRecord> {
        simulatedFailure?.let { return Result.failure(it) }

        val cleanFeature = feature.trim().uppercase()
        if (!FeaturePermissionMapper.isValidDatabaseFeature(cleanFeature)) {
            return Result.failure(
                IllegalArgumentException("Invalid feature: '$feature'. Supported: ${FeaturePermissionMapper.ALL_DATABASE_FEATURES}")
            )
        }

        val assignment = assignments.firstOrNull { it.userId == userId && it.deviceId == deviceId && it.status == "ACTIVE" }
            ?: return Result.failure(
                NoSuchElementException("No active device assignment found for user '$userId' and device '$deviceId'")
            )

        val assignmentId = assignment.id ?: return Result.failure(IllegalStateException("Assignment id is null"))

        permissions.removeIf { it.assignmentId == assignmentId && it.feature == cleanFeature }

        val newRecord = UserFeaturePermissionRecord(
            id = idGenerator.incrementAndGet(),
            assignmentId = assignmentId,
            deviceId = deviceId,
            feature = cleanFeature,
            isAllowed = true,
            grantedAt = java.time.Instant.now().toString(),
            expiresAt = expiresAt
        )
        permissions.add(newRecord)
        return Result.success(newRecord)
    }

    override suspend fun revokeFeaturePermission(
        userId: String,
        deviceId: String,
        feature: String
    ): Result<Unit> {
        simulatedFailure?.let { return Result.failure(it) }

        val cleanFeature = feature.trim().uppercase()
        val assignment = assignments.firstOrNull { it.userId == userId && it.deviceId == deviceId && it.status == "ACTIVE" }
        if (assignment != null && assignment.id != null) {
            val index = permissions.indexOfFirst { it.assignmentId == assignment.id && it.feature == cleanFeature }
            if (index >= 0) {
                val existing = permissions[index]
                permissions[index] = existing.copy(isAllowed = false)
            }
        }
        return Result.success(Unit)
    }
}
