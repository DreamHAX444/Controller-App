package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseUserManagementRepositoryRoleGuardTest {

    private var clientProviderInvoked = false
    private var activeSession: Session? = null

    private class TestPostgrestException : RuntimeException("PostgREST reached successfully")

    private fun createSession(
        role: Role,
        isValid: Boolean = true,
        status: AccountStatus = AccountStatus.ACTIVE
    ): Session {
        val user = User(
            id = "admin-user",
            displayName = "Admin User",
            role = role,
            accountStatus = status,
            devicePermissions = emptyList()
        )
        val now = System.currentTimeMillis()
        return Session(
            sessionId = "sess-repo-test",
            user = user,
            isAuthenticated = isValid,
            createdAt = now - 10_000L,
            expiresAt = if (isValid) now + 60_000L else now - 1000L
        )
    }

    private fun createRepository(withSessionProvider: Boolean = true): SupabaseUserManagementRepository {
        return SupabaseUserManagementRepository(
            clientProvider = {
                clientProviderInvoked = true
                throw TestPostgrestException()
            },
            sessionProvider = if (withSessionProvider) { { activeSession } } else null
        )
    }

    @Before
    fun setup() {
        clientProviderInvoked = false
        activeSession = null
    }

    // ==========================================
    // 1. approveUser Role Guards
    // ==========================================

    @Test
    fun `approveUser rejects USER role with SecurityException`() = runTest {
        activeSession = createSession(Role.USER)
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUser rejects VISITOR role with SecurityException`() = runTest {
        activeSession = createSession(Role.VISITOR)
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUser rejects expired session with SecurityException`() = runTest {
        activeSession = createSession(Role.ADMIN, isValid = false)
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUser rejects null session with SecurityException`() = runTest {
        activeSession = null
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `approveUser allows OWNER role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.OWNER)
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `approveUser allows ADMIN role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.ADMIN)
        val repo = createRepository()

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `approveUser without sessionProvider proceeds directly to client`() = runTest {
        val repo = createRepository(withSessionProvider = false)

        val result = repo.approveUser("target-user")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 2. updateUserStatus Role Guards
    // ==========================================

    @Test
    fun `updateUserStatus rejects USER role with SecurityException`() = runTest {
        activeSession = createSession(Role.USER)
        val repo = createRepository()

        val result = repo.updateUserStatus("target-user", AccountStatus.SUSPENDED)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus rejects VISITOR role with SecurityException`() = runTest {
        activeSession = createSession(Role.VISITOR)
        val repo = createRepository()

        val result = repo.updateUserStatus("target-user", AccountStatus.SUSPENDED)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus allows OWNER role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.OWNER)
        val repo = createRepository()

        val result = repo.updateUserStatus("target-user", AccountStatus.ACTIVE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `updateUserStatus allows ADMIN role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.ADMIN)
        val repo = createRepository()

        val result = repo.updateUserStatus("target-user", AccountStatus.SUSPENDED)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 3. updateUserRole Role Guards
    // ==========================================

    @Test
    fun `updateUserRole rejects USER role with SecurityException`() = runTest {
        activeSession = createSession(Role.USER)
        val repo = createRepository()

        val result = repo.updateUserRole("target-user", Role.ADMIN)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole rejects VISITOR role with SecurityException`() = runTest {
        activeSession = createSession(Role.VISITOR)
        val repo = createRepository()

        val result = repo.updateUserRole("target-user", Role.ADMIN)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole allows OWNER role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.OWNER)
        val repo = createRepository()

        val result = repo.updateUserRole("target-user", Role.ADMIN)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `updateUserRole allows ADMIN role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.ADMIN)
        val repo = createRepository()

        val result = repo.updateUserRole("target-user", Role.USER)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 4. grantFeaturePermission Role Guards
    // ==========================================

    @Test
    fun `grantFeaturePermission rejects USER role with SecurityException`() = runTest {
        activeSession = createSession(Role.USER)
        val repo = createRepository()

        val result = repo.grantFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `grantFeaturePermission rejects VISITOR role with SecurityException`() = runTest {
        activeSession = createSession(Role.VISITOR)
        val repo = createRepository()

        val result = repo.grantFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `grantFeaturePermission allows OWNER role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.OWNER)
        val repo = createRepository()

        val result = repo.grantFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `grantFeaturePermission allows ADMIN role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.ADMIN)
        val repo = createRepository()

        val result = repo.grantFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    // ==========================================
    // 5. revokeFeaturePermission Role Guards
    // ==========================================

    @Test
    fun `revokeFeaturePermission rejects USER role with SecurityException`() = runTest {
        activeSession = createSession(Role.USER)
        val repo = createRepository()

        val result = repo.revokeFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Only Owner or Admin can perform this user management operation", result.exceptionOrNull()?.message)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `revokeFeaturePermission rejects VISITOR role with SecurityException`() = runTest {
        activeSession = createSession(Role.VISITOR)
        val repo = createRepository()

        val result = repo.revokeFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertFalse(clientProviderInvoked)
    }

    @Test
    fun `revokeFeaturePermission allows OWNER role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.OWNER)
        val repo = createRepository()

        val result = repo.revokeFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }

    @Test
    fun `revokeFeaturePermission allows ADMIN role and proceeds to PostgREST`() = runTest {
        activeSession = createSession(Role.ADMIN)
        val repo = createRepository()

        val result = repo.revokeFeaturePermission("target-user", "dev-1", "CAMERA")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is TestPostgrestException)
        assertTrue(clientProviderInvoked)
    }
}
