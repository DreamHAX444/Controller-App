package com.livetracker.controller.ui.screens.users

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.DeviceFeaturePermissionRecord
import com.livetracker.controller.core.tracker.supabase.DeviceRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseFleetManager
import com.livetracker.controller.core.tracker.supabase.UserProfileRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSessionRepository(initialSession: Session? = null) : SessionRepository {
        private val _session = MutableStateFlow(initialSession)

        override suspend fun createSession(session: Session): Result<Unit> {
            _session.value = session
            return Result.success(Unit)
        }

        override suspend fun getActiveSession(): Result<Session> {
            val curr = _session.value ?: return Result.failure(IllegalStateException("No active session"))
            return Result.success(curr)
        }

        override suspend fun updateSessionUser(user: com.livetracker.controller.core.auth.domain.User): Result<Unit> {
            val curr = _session.value ?: return Result.failure(IllegalStateException("No active session"))
            _session.value = curr.copy(user = user)
            return Result.success(Unit)
        }

        override suspend fun invalidateSession(): Result<Unit> {
            _session.value = null
            return Result.success(Unit)
        }

        override fun observeActiveSession(): Flow<Session?> = _session.asStateFlow()

        fun setSession(session: Session?) {
            _session.value = session
        }
    }

    private class FakeSupabaseFleetManager(
        initialProfiles: List<UserProfileRecord> = emptyList(),
        initialAssignments: List<DeviceAssignmentRecord> = emptyList(),
        initialPermissions: List<DeviceFeaturePermissionRecord> = emptyList(),
        initialDevices: List<DeviceRecord> = emptyList()
    ) : SupabaseFleetManager(
        clientProvider = { throw UnsupportedOperationException("No real Supabase client in unit tests") },
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
    ) {
        val profiles = initialProfiles.toMutableList()
        val assignments = initialAssignments.toMutableList()
        val permissions = initialPermissions.toMutableList()
        val devices = initialDevices.toMutableList()

        var lastUpdatedRoleUser: String? = null
        var lastUpdatedRole: String? = null
        var lastUpdatedStatusUser: String? = null
        var lastUpdatedStatus: String? = null
        var lastAssignedDevice: String? = null
        var lastAssignedUser: String? = null
        var lastUnassignedDevice: String? = null
        var lastUnassignedUser: String? = null
        var lastDeletedUserId: String? = null
        var lastUpdatedPermissionAssignmentId: Long? = null
        var lastUpdatedPermissionFeature: String? = null
        var lastUpdatedPermissionAllowed: Boolean? = null

        override suspend fun fetchAllProfiles(): List<UserProfileRecord> = profiles.toList()
        override suspend fun fetchFleetDevices(): List<DeviceRecord> = devices.toList()
        override suspend fun fetchDeviceAssignments(): List<DeviceAssignmentRecord> = assignments.toList()
        override suspend fun fetchDeviceFeaturePermissions(): List<DeviceFeaturePermissionRecord> = permissions.toList()

        override suspend fun approveUserAccount(userId: String): Result<Unit> {
            lastUpdatedRoleUser = userId
            lastUpdatedRole = Role.USER.name
            lastUpdatedStatusUser = userId
            lastUpdatedStatus = AccountStatus.ACTIVE.name
            val index = profiles.indexOfFirst { it.id == userId }
            if (index >= 0) {
                profiles[index] = profiles[index].copy(role = Role.USER.name, status = AccountStatus.ACTIVE.name)
            }
            return Result.success(Unit)
        }

        override suspend fun updateUserRole(userId: String, newRole: String): Result<Unit> {
            lastUpdatedRoleUser = userId
            lastUpdatedRole = newRole
            val index = profiles.indexOfFirst { it.id == userId }
            if (index >= 0) {
                profiles[index] = profiles[index].copy(role = newRole)
            }
            return Result.success(Unit)
        }

        override suspend fun updateUserStatus(userId: String, newStatus: String): Result<Unit> {
            lastUpdatedStatusUser = userId
            lastUpdatedStatus = newStatus
            val index = profiles.indexOfFirst { it.id == userId }
            if (index >= 0) {
                profiles[index] = profiles[index].copy(status = newStatus)
            }
            return Result.success(Unit)
        }

        override suspend fun assignDeviceToUser(deviceId: String, userId: String, expiresAt: String?): Result<Unit> {
            lastAssignedDevice = deviceId
            lastAssignedUser = userId
            val nextId = (assignments.maxOfOrNull { it.id ?: 0L } ?: 0L) + 1L
            assignments.add(DeviceAssignmentRecord(id = nextId, userId = userId, deviceId = deviceId, status = "ACTIVE"))
            return Result.success(Unit)
        }

        override suspend fun unassignDeviceFromUser(deviceId: String, userId: String): Result<Unit> {
            lastUnassignedDevice = deviceId
            lastUnassignedUser = userId
            assignments.removeAll { it.deviceId == deviceId && it.userId == userId }
            return Result.success(Unit)
        }

        override suspend fun deleteUserAccount(userId: String): Result<Unit> {
            lastDeletedUserId = userId
            profiles.removeAll { it.id == userId }
            assignments.removeAll { it.userId == userId }
            return Result.success(Unit)
        }

        override suspend fun updateDeviceFeaturePermission(
            assignmentId: Long,
            feature: String,
            isAllowed: Boolean,
            userId: String?,
            deviceId: String?
        ): Result<Unit> {
            lastUpdatedPermissionAssignmentId = assignmentId
            lastUpdatedPermissionFeature = feature
            lastUpdatedPermissionAllowed = isAllowed

            permissions.removeAll { it.assignmentId == assignmentId && it.feature == feature }
            permissions.add(
                DeviceFeaturePermissionRecord(
                    id = (permissions.maxOfOrNull { it.id ?: 0L } ?: 0L) + 1L,
                    assignmentId = assignmentId,
                    feature = feature,
                    isAllowed = isAllowed
                )
            )
            return Result.success(Unit)
        }
    }

    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var fakeFleetManager: FakeSupabaseFleetManager

    private fun createSession(role: Role, userId: String = "admin-1"): Session {
        val user = User(
            id = userId,
            displayName = "Session $role",
            role = role,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-$userId",
            user = user,
            isAuthenticated = true,
            createdAt = 1000L,
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionRepository = FakeSessionRepository(createSession(Role.ADMIN))
        fakeFleetManager = FakeSupabaseFleetManager(
            initialProfiles = listOf(
                UserProfileRecord(id = "u-owner", email = "owner@trace.local", displayName = "Commander", role = "OWNER", status = "ACTIVE"),
                UserProfileRecord(id = "admin-1", email = "admin@trace.local", displayName = "Security Admin", role = "ADMIN", status = "ACTIVE"),
                UserProfileRecord(id = "u-user", email = "operator@trace.local", displayName = "Field Operator", role = "USER", status = "ACTIVE"),
                UserProfileRecord(id = "u-visitor", email = "applicant@trace.local", displayName = "New Applicant", role = "VISITOR", status = "ACTIVE"),
                UserProfileRecord(id = "u-suspended", email = "suspended@trace.local", displayName = "Suspended Analyst", role = "USER", status = "SUSPENDED")
            ),
            initialDevices = listOf(
                DeviceRecord(deviceId = "dev-1", name = "Tactical Rover 1", model = "Pixel 8"),
                DeviceRecord(deviceId = "dev-2", name = "Tactical Rover 2", model = "Pixel 7")
            ),
            initialAssignments = listOf(
                DeviceAssignmentRecord(id = 101L, userId = "u-user", deviceId = "dev-1", status = "ACTIVE")
            ),
            initialPermissions = listOf(
                DeviceFeaturePermissionRecord(id = 1L, assignmentId = 101L, feature = "LOCATION", isAllowed = true),
                DeviceFeaturePermissionRecord(id = 2L, assignmentId = 101L, feature = "CAMERA", isAllowed = true)
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads all users and counts accurately for admin`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(5, state.totalCount)
        assertEquals(1, state.visitorsCount)
        assertEquals(3, state.activeCount)
        assertEquals(1, state.suspendedDisabledCount)
        assertFalse(state.isAccessDenied)
        assertEquals(5, state.users.size)

        val userItem = state.users.find { it.userId == "u-user" }
        assertNotNull(userItem)
        assertEquals(1, userItem?.assignedDevicesCount)
        assertEquals(2, userItem?.activePermissionsCount)

        val adminItem = state.users.find { it.userId == "admin-1" }
        assertNotNull(adminItem)
        assertTrue(adminItem?.isAdmin == true)
        assertEquals(2, adminItem?.assignedDevicesCount)
        assertEquals(12, adminItem?.activePermissionsCount)

        collectJob.cancel()
    }

    @Test
    fun `visitor and standard user role fails closed with Access Denied banner`() = runTest {
        sessionRepository.setSession(createSession(Role.USER, userId = "u-user"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertTrue(state.isAccessDenied)
        assertTrue(state.users.isEmpty())
        assertEquals(0, state.totalCount)

        collectJob.cancel()
    }

    @Test
    fun `search query filters users by email, name, or id`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        // Search by name
        viewModel.onSearchQueryChanged("Commander")
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(1, state.users.size)
        assertEquals("u-owner", state.users.first().userId)

        // Search by email
        viewModel.onSearchQueryChanged("applicant@")
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.users.size)
        assertEquals("u-visitor", state.users.first().userId)

        // Clear search
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(5, state.users.size)

        collectJob.cancel()
    }

    @Test
    fun `filter chips filter users by category`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        // VISITORS filter
        viewModel.onFilterSelected(UserFilter.VISITORS)
        advanceUntilIdle()
        var state = viewModel.uiState.value
        assertEquals(1, state.users.size)
        assertEquals("u-visitor", state.users.first().userId)

        // ACTIVE filter
        viewModel.onFilterSelected(UserFilter.ACTIVE)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(3, state.users.size)

        // SUSPENDED_DISABLED filter
        viewModel.onFilterSelected(UserFilter.SUSPENDED_DISABLED)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.users.size)
        assertEquals("u-suspended", state.users.first().userId)

        collectJob.cancel()
    }

    @Test
    fun `onPromoteVisitorToUser promotes visitor to USER and opens manage dialog`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val visitorUser = viewModel.uiState.value.users.find { it.userId == "u-visitor" }!!
        viewModel.onPromoteVisitorToUser(visitorUser)
        advanceUntilIdle()

        assertEquals("u-visitor", fakeFleetManager.lastUpdatedRoleUser)
        assertEquals(Role.USER.name, fakeFleetManager.lastUpdatedRole)

        val state = viewModel.uiState.value
        assertEquals("u-visitor", state.selectedUserForManagement?.userId)

        collectJob.cancel()
    }

    @Test
    fun `admin cannot promote anyone to OWNER`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onChangeRole("u-user", Role.OWNER)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("cannot promote", ignoreCase = true) == true)
        assertNull(fakeFleetManager.lastUpdatedRole)

        collectJob.cancel()
    }

    @Test
    fun `admin cannot demote or modify OWNER`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onChangeRole("u-owner", Role.USER)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("Administrator cannot modify", ignoreCase = true) == true)
        assertNull(fakeFleetManager.lastUpdatedRole)

        collectJob.cancel()
    }

    @Test
    fun `admin cannot demote USER to VISITOR`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onChangeRole("u-user", Role.VISITOR)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("only Visitor to User promotion allowed", ignoreCase = true) == true)
        assertNull(fakeFleetManager.lastUpdatedRole)

        collectJob.cancel()
    }

    @Test
    fun `user cannot modify own role`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onChangeRole("admin-1", Role.USER)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("cannot modify your own role", ignoreCase = true) == true)

        collectJob.cancel()
    }

    @Test
    fun `status change to SUSPENDED triggers confirmation dialog for owner`() = runTest {
        sessionRepository.setSession(createSession(Role.OWNER, userId = "u-owner"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val userItem = viewModel.uiState.value.users.find { it.userId == "u-user" }!!
        viewModel.onRequestStatusChange(userItem, AccountStatus.SUSPENDED)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.userToConfirmStatusChange)
        assertEquals("u-user", state.userToConfirmStatusChange?.first?.userId)
        assertEquals(AccountStatus.SUSPENDED, state.userToConfirmStatusChange?.second)

        // Confirm
        viewModel.onConfirmStatusChange()
        advanceUntilIdle()

        assertEquals("u-user", fakeFleetManager.lastUpdatedStatusUser)
        assertEquals(AccountStatus.SUSPENDED.name, fakeFleetManager.lastUpdatedStatus)

        collectJob.cancel()
    }

    @Test
    fun `admin cannot modify account status`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val userItem = viewModel.uiState.value.users.find { it.userId == "u-user" }!!
        viewModel.onRequestStatusChange(userItem, AccountStatus.SUSPENDED)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.userToConfirmStatusChange)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage?.contains("Only Fleet Owners", ignoreCase = true) == true)

        collectJob.cancel()
    }

    @Test
    fun `assign and unassign device updates user assignments`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        // Assign dev-2 to u-user
        viewModel.onAssignDevice("u-user", "dev-2")
        advanceUntilIdle()

        assertEquals("dev-2", fakeFleetManager.lastAssignedDevice)
        assertEquals("u-user", fakeFleetManager.lastAssignedUser)

        // Unassign dev-1 from u-user
        viewModel.onUnassignDevice("u-user", "dev-1")
        advanceUntilIdle()

        assertEquals("dev-1", fakeFleetManager.lastUnassignedDevice)
        assertEquals("u-user", fakeFleetManager.lastUnassignedUser)

        collectJob.cancel()
    }

    @Test
    fun `onToggleFeaturePermission updates feature permission record`() = runTest {
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onToggleFeaturePermission(101L, "CAMERA", false)
        advanceUntilIdle()

        assertEquals(101L, fakeFleetManager.lastUpdatedPermissionAssignmentId)
        assertEquals("CAMERA", fakeFleetManager.lastUpdatedPermissionFeature)
        assertEquals(false, fakeFleetManager.lastUpdatedPermissionAllowed)

        collectJob.cancel()
    }

    // ==========================================
    // User Deletion Tests (Owner-Only)
    // ==========================================

    @Test
    fun `owner can request and confirm user account deletion`() = runTest {
        sessionRepository.setSession(createSession(Role.OWNER, "u-owner"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val target = viewModel.uiState.value.users.first { it.userId == "u-user" }
        viewModel.onRequestDeleteUser(target)
        advanceUntilIdle()

        assertEquals("u-user", viewModel.uiState.value.userToConfirmDelete?.userId)

        viewModel.onConfirmDeleteUser()
        advanceUntilIdle()

        assertEquals("u-user", fakeFleetManager.lastDeletedUserId)
        assertNull(viewModel.uiState.value.userToConfirmDelete)
        assertTrue(viewModel.uiState.value.statusMessage?.contains("deleted successfully") == true)

        collectJob.cancel()
    }

    @Test
    fun `admin cannot delete user account`() = runTest {
        sessionRepository.setSession(createSession(Role.ADMIN, "admin-1"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val target = viewModel.uiState.value.users.first { it.userId == "u-user" }
        viewModel.onRequestDeleteUser(target)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.userToConfirmDelete)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("Only Fleet Owner can delete user accounts") == true)

        collectJob.cancel()
    }

    @Test
    fun `owner cannot delete self`() = runTest {
        sessionRepository.setSession(createSession(Role.OWNER, "u-owner"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val self = viewModel.uiState.value.users.first { it.userId == "u-owner" }
        viewModel.onRequestDeleteUser(self)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.userToConfirmDelete)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("You cannot delete your own account") == true)

        collectJob.cancel()
    }

    @Test
    fun `owner cannot delete another owner`() = runTest {
        sessionRepository.setSession(createSession(Role.OWNER, "u-owner-1"))
        val viewModel = UsersViewModel(sessionRepository, fakeFleetManager)
        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val anotherOwner = viewModel.uiState.value.users.first { it.userId == "u-owner" }
        viewModel.onRequestDeleteUser(anotherOwner)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.userToConfirmDelete)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("Owner accounts cannot be deleted") == true)

        collectJob.cancel()
    }
}
