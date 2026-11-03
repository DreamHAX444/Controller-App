package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.data.model.ManagedUserRecord
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UserManagementRepositoryTest {

    private lateinit var repository: FakeUserManagementRepository

    @Before
    fun setUp() {
        repository = FakeUserManagementRepository()
    }

    @Test
    fun `fetchUsers returns all seeded users by default`() = runTest {
        val result = repository.fetchUsers()
        assertTrue(result.isSuccess)
        val users = result.getOrThrow()
        assertTrue(users.size >= 6)
        assertTrue(users.any { it.role == Role.OWNER })
        assertTrue(users.any { it.role == Role.ADMIN })
        assertTrue(users.any { it.role == Role.USER })
        assertTrue(users.any { it.status == AccountStatus.PENDING })
    }

    @Test
    fun `fetchUsers filters accurately by role`() = runTest {
        val adminResult = repository.fetchUsers(roleFilter = Role.ADMIN)
        assertTrue(adminResult.isSuccess)
        val admins = adminResult.getOrThrow()
        assertTrue(admins.isNotEmpty())
        assertTrue(admins.all { it.role == Role.ADMIN })
    }

    @Test
    fun `fetchUsers filters accurately by account status`() = runTest {
        val pendingResult = repository.fetchUsers(statusFilter = AccountStatus.PENDING)
        assertTrue(pendingResult.isSuccess)
        val pendingUsers = pendingResult.getOrThrow()
        assertEquals(1, pendingUsers.size)
        assertEquals(AccountStatus.PENDING, pendingUsers.first().status)
        assertTrue(pendingUsers.first().pendingApproval)
    }

    @Test
    fun `fetchUsers filters accurately by text search query`() = runTest {
        val searchResult = repository.fetchUsers(query = "Fleet Admin")
        assertTrue(searchResult.isSuccess)
        val users = searchResult.getOrThrow()
        assertEquals(1, users.size)
        assertEquals("u1", users.first().id)
        assertEquals("admin@trace.local", users.first().email)
    }

    @Test
    fun `approveUser upgrades pending visitor to active user with pendingApproval false`() = runTest {
        val result = repository.approveUser("u6")
        assertTrue(result.isSuccess)
        val approved = result.getOrThrow()
        assertEquals("u6", approved.id)
        assertEquals(AccountStatus.ACTIVE, approved.status)
        assertEquals(Role.USER, approved.role)
        assertFalse(approved.pendingApproval)

        // Verify updated in repository
        val fetched = repository.fetchUsers(statusFilter = AccountStatus.PENDING).getOrThrow()
        assertTrue(fetched.none { it.id == "u6" })
    }

    @Test
    fun `updateUserStatus changes status and pendingApproval flag appropriately`() = runTest {
        val result = repository.updateUserStatus("u2", AccountStatus.SUSPENDED)
        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals(AccountStatus.SUSPENDED, updated.status)
        assertFalse(updated.pendingApproval)
    }

    @Test
    fun `updateUserRole elevates user role`() = runTest {
        val result = repository.updateUserRole("u2", Role.ADMIN)
        assertTrue(result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals(Role.ADMIN, updated.role)
    }

    @Test
    fun `fetchUserAssignments returns assigned devices for user`() = runTest {
        val result = repository.fetchUserAssignments("u2")
        assertTrue(result.isSuccess)
        val assignments = result.getOrThrow()
        assertEquals(1, assignments.size)
        assertEquals("mock-tracker-1", assignments.first().deviceId)
    }

    @Test
    fun `fetchUserPermissions returns active feature permissions for assigned device`() = runTest {
        val result = repository.fetchUserPermissions("u2", "mock-tracker-1")
        assertTrue(result.isSuccess)
        val permissions = result.getOrThrow()
        assertEquals(5, permissions.size)
        assertTrue(permissions.any { it.feature == "LOCATION" && it.isAllowed })
        assertTrue(permissions.any { it.feature == "CAMERA" && it.isAllowed })
    }

    @Test
    fun `grantFeaturePermission adds or updates permission record`() = runTest {
        // Grant COMMANDS permission
        val grantResult = repository.grantFeaturePermission("u2", "mock-tracker-1", "COMMANDS")
        assertTrue(grantResult.isSuccess)
        val granted = grantResult.getOrThrow()
        assertEquals("COMMANDS", granted.feature)
        assertTrue(granted.isAllowed)

        val updatedPermissions = repository.fetchUserPermissions("u2", "mock-tracker-1").getOrThrow()
        assertTrue(updatedPermissions.any { it.feature == "COMMANDS" && it.isAllowed })
    }

    @Test
    fun `grantFeaturePermission fails closed on invalid feature string`() = runTest {
        val grantResult = repository.grantFeaturePermission("u2", "mock-tracker-1", "INVALID_FEATURE")
        assertTrue(grantResult.isFailure)
        assertTrue(grantResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `revokeFeaturePermission marks permission as disallowed`() = runTest {
        val revokeResult = repository.revokeFeaturePermission("u2", "mock-tracker-1", "CAMERA")
        assertTrue(revokeResult.isSuccess)

        val permissions = repository.fetchUserPermissions("u2", "mock-tracker-1").getOrThrow()
        val cameraPerm = permissions.firstOrNull { it.feature == "CAMERA" }
        assertNotNull(cameraPerm)
        assertFalse(cameraPerm!!.isAllowed)
    }

    @Test
    fun `simulatedFailure produces failure across repository calls`() = runTest {
        repository.setSimulatedFailure(IllegalStateException("Simulated network outage"))
        val result = repository.fetchUsers()
        assertTrue(result.isFailure)
        assertEquals("Simulated network outage", result.exceptionOrNull()?.message)
    }
}
