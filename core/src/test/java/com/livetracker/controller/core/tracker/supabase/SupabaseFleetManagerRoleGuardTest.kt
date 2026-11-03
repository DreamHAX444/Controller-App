package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseFleetManagerRoleGuardTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var clientProviderInvoked = false
    private lateinit var fleetManager: SupabaseFleetManager
    private val currentTime = 10_000L

    private class TestRpcException : RuntimeException("RPC reached successfully")

    @Before
    fun setup() {
        clientProviderInvoked = false
        fleetManager = SupabaseFleetManager(
            clientProvider = {
                clientProviderInvoked = true
                throw TestRpcException()
            },
            scope = testScope,
            timeProvider = { currentTime }
        )
    }

    private fun createSession(
        role: Role,
        isValid: Boolean = true,
        status: AccountStatus = AccountStatus.ACTIVE
    ): Session {
        val user = User(
            id = "test-user-id",
            displayName = "Test User",
            role = role,
            accountStatus = status,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-role-guard-test",
            user = user,
            isAuthenticated = isValid,
            createdAt = 1000L,
            expiresAt = if (isValid) currentTime + 60_000L else currentTime - 1000L
        )
    }

    // ==========================================
    // 1. updateUserRole Role Guards
    // ==========================================

    @Test
    fun `updateUserRole rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.updateUserRole("u1", "ADMIN")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can update user role", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.updateUserRole("u1", "ADMIN")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can update user role", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole rejects expired session with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN, isValid = false))

        val result = fleetManager.updateUserRole("u1", "ADMIN")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole rejects null session with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(null)

        val result = fleetManager.updateUserRole("u1", "ADMIN")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.updateUserRole("u1", "ADMIN")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.updateUserRole("u1", "USER")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 2. updateUserStatus Role Guards
    // ==========================================

    @Test
    fun `updateUserStatus rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.updateUserStatus("u1", "ACTIVE")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can update user status", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.updateUserStatus("u1", "ACTIVE")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can update user status", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.updateUserStatus("u1", "ACTIVE")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.updateUserStatus("u1", "SUSPENDED")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 3. assignDeviceToUser Role Guards
    // ==========================================

    @Test
    fun `assignDeviceToUser rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.assignDeviceToUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can assign devices", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `assignDeviceToUser rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.assignDeviceToUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can assign devices", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `assignDeviceToUser allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.assignDeviceToUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `assignDeviceToUser allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.assignDeviceToUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 4. unassignDeviceFromUser Role Guards
    // ==========================================

    @Test
    fun `unassignDeviceFromUser rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.unassignDeviceFromUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can unassign devices", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `unassignDeviceFromUser rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.unassignDeviceFromUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can unassign devices", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `unassignDeviceFromUser allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.unassignDeviceFromUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `unassignDeviceFromUser allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.unassignDeviceFromUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 5. updateDeviceFeaturePermission Role Guards
    // ==========================================

    @Test
    fun `updateDeviceFeaturePermission rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.updateDeviceFeaturePermission(
            assignmentId = 1L,
            feature = "CAMERA",
            isAllowed = true
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can modify device feature permissions", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateDeviceFeaturePermission rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.updateDeviceFeaturePermission(
            assignmentId = 1L,
            feature = "CAMERA",
            isAllowed = true
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can modify device feature permissions", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateDeviceFeaturePermission allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.updateDeviceFeaturePermission(
            assignmentId = 1L,
            feature = "CAMERA",
            isAllowed = true
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `updateDeviceFeaturePermission allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.updateDeviceFeaturePermission(
            assignmentId = 1L,
            feature = "CAMERA",
            isAllowed = true
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 6. fetchAllProfiles Role Guards
    // ==========================================

    @Test
    fun `fetchAllProfiles returns emptyList for USER role without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val profiles = fleetManager.fetchAllProfiles()

        assertTrue(profiles.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAllProfiles returns emptyList for VISITOR role without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val profiles = fleetManager.fetchAllProfiles()

        assertTrue(profiles.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAllProfiles returns emptyList for null session without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(null)

        val profiles = fleetManager.fetchAllProfiles()

        assertTrue(profiles.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAllProfiles allows OWNER role and calls Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        fleetManager.fetchAllProfiles()

        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `fetchAllProfiles allows ADMIN role and calls Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        fleetManager.fetchAllProfiles()

        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 7. fetchAssignableUsers Role Guards
    // ==========================================

    @Test
    fun `fetchAssignableUsers returns emptyList for USER role without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val users = fleetManager.fetchAssignableUsers()

        assertTrue(users.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAssignableUsers returns emptyList for VISITOR role without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val users = fleetManager.fetchAssignableUsers()

        assertTrue(users.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAssignableUsers returns emptyList for null session without calling Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(null)

        val users = fleetManager.fetchAssignableUsers()

        assertTrue(users.isEmpty())
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `fetchAssignableUsers allows OWNER role and calls Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        fleetManager.fetchAssignableUsers()

        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `fetchAssignableUsers allows ADMIN role and calls Supabase`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        fleetManager.fetchAssignableUsers()

        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 7. approveUserAccount Role Guards
    // ==========================================

    @Test
    fun `approveUserAccount rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.approveUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can approve user account", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUserAccount rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.approveUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can approve user account", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUserAccount allows ADMIN role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.approveUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `approveUserAccount allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.approveUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 7. deleteUserAccount Role Guards
    // ==========================================

    @Test
    fun `deleteUserAccount rejects USER role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.USER))

        val result = fleetManager.deleteUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Fleet Owner can delete user accounts", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `deleteUserAccount rejects ADMIN role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.ADMIN))

        val result = fleetManager.deleteUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Fleet Owner can delete user accounts", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `deleteUserAccount rejects VISITOR role with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.VISITOR))

        val result = fleetManager.deleteUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Fleet Owner can delete user accounts", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `deleteUserAccount rejects expired session with SecurityException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER, isValid = false))

        val result = fleetManager.deleteUserAccount("u1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `deleteUserAccount rejects self-deletion with IllegalArgumentException`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.deleteUserAccount("test-user-id")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Cannot delete your own account", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `deleteUserAccount allows OWNER role and proceeds to RPC`() = testScope.runTest {
        fleetManager.setCurrentSessionForTesting(createSession(Role.OWNER))

        val result = fleetManager.deleteUserAccount("u-other-id")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestRpcException)
        assertTrue(clientProviderInvoked)
    }
}
