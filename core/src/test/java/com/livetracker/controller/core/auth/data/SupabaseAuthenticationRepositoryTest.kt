package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.AuthenticationResult
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseAuthenticationRepositoryTest {

    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var credentialStore: FakeCredentialStore
    private lateinit var repository: SupabaseAuthenticationRepository

    @Before
    fun setUp() {
        sessionRepository = FakeSessionRepository()
        credentialStore = FakeCredentialStore()
        repository = SupabaseAuthenticationRepository(
            sessionRepository = sessionRepository,
            credentialStore = credentialStore
        )
    }

    @Test
    fun `authenticate with developer presets is strictly blocked and returns InvalidCredentials`() = runTest {
        val legacyPresets = listOf("owner", "admin", "user", "visitor", "pending", "disabled", "suspended", "unavailable")
        for (preset in legacyPresets) {
            val result = repository.authenticate(preset)
            assertEquals("Preset '$preset' must be rejected as InvalidCredentials", AuthenticationResult.InvalidCredentials, result)
            assertNull("Session must not be created for preset '$preset'", sessionRepository.activeSession)
        }
    }

    @Test
    fun `authenticate with blank credential returns InvalidRequest`() = runTest {
        val result = repository.authenticate("   ")
        assertEquals(AuthenticationResult.InvalidRequest, result)
    }

    @Test
    fun `authenticate with blank signup returns InvalidRequest`() = runTest {
        val result = repository.authenticate("signup:   |   |   ")
        assertEquals(AuthenticationResult.InvalidRequest, result)
    }

    @Test
    fun `authenticate with legacy synthetic google token is strictly rejected`() = runTest {
        val syntheticToken = "google:test@gmail.com|Test User|https://avatar.url|google_auth_test_gmail_com"
        val result = repository.authenticate(syntheticToken)
        assertTrue("Synthetic token must return Error", result is AuthenticationResult.Error)
        assertNull(sessionRepository.activeSession)
        assertNull(credentialStore.storedToken)
    }

    @Test
    fun `RestoreSessionUseCase with legacy synthetic google token purges credential store and returns Unauthenticated`() = runTest {
        val syntheticToken = "google:test@gmail.com|Test User|https://avatar.url|google_auth_test_gmail_com"
        credentialStore.storeSecureToken(syntheticToken)
        val restoreUseCase = com.livetracker.controller.core.auth.domain.usecase.RestoreSessionUseCase(
            credentialStore = credentialStore,
            authRepository = repository
        )
        val state = restoreUseCase()
        assertEquals(com.livetracker.controller.core.auth.presentation.AuthenticationState.Unauthenticated, state)
        assertNull("CredentialStore must be purged of synthetic token", credentialStore.storedToken)
    }

    @Test
    fun `logout clears session and credential store`() = runTest {
        val user = com.livetracker.controller.core.auth.domain.User(
            id = "u0",
            displayName = "test@trace.local",
            role = Role.OWNER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        val session = com.livetracker.controller.core.auth.domain.Session(
            sessionId = "sess_${UUID.randomUUID()}",
            user = user,
            isAuthenticated = true,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
        sessionRepository.createSession(session)
        credentialStore.storeSecureToken("mock_jwt_token")

        assertNotNull(sessionRepository.activeSession)
        assertEquals("mock_jwt_token", credentialStore.storedToken)

        val logoutResult = repository.logout()
        assertTrue(logoutResult.isSuccess)
        assertNull(sessionRepository.activeSession)
        assertNull(credentialStore.storedToken)
    }

    @Test
    fun `authenticate with explicit id token fails gracefully if service unavailable`() = runTest {
        val result = repository.authenticate("id_token:non_existent_mock_token")
        assertTrue(
            "Expected failure result, got $result",
            result is AuthenticationResult.InvalidCredentials || result is AuthenticationResult.AuthenticationUnavailable
        )
    }

    @Test
    fun `authenticate with saved access token fails gracefully if service unavailable`() = runTest {
        val result = repository.authenticate("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummy.sig")
        assertTrue(
            "Expected failure result, got $result",
            result is AuthenticationResult.InvalidCredentials ||
            result is AuthenticationResult.SessionExpired ||
            result is AuthenticationResult.AuthenticationUnavailable
        )
    }

    @Test
    fun `ProfileUpsert default values set status to ACTIVE and role to VISITOR`() {
        val upsert = com.livetracker.controller.core.auth.data.model.ProfileUpsert(
            id = "test-uuid",
            email = "newuser@trace.local"
        )
        assertEquals("ACTIVE", upsert.status)
        assertEquals("VISITOR", upsert.role)
    }

    private class FakeSessionRepository : SessionRepository {
        var activeSession: com.livetracker.controller.core.auth.domain.Session? = null

        override fun observeActiveSession() = flowOf(activeSession)
        override suspend fun getActiveSession() = activeSession?.let { Result.success(it) } ?: Result.failure(Exception("No session"))
        override suspend fun createSession(session: com.livetracker.controller.core.auth.domain.Session): Result<Unit> {
            activeSession = session
            return Result.success(Unit)
        }
        override suspend fun updateSessionUser(user: com.livetracker.controller.core.auth.domain.User): Result<Unit> {
            activeSession = activeSession?.copy(user = user)
            return Result.success(Unit)
        }
        override suspend fun invalidateSession(): Result<Unit> {
            activeSession = null
            return Result.success(Unit)
        }
    }

    private class FakeCredentialStore : CredentialStore {
        var storedToken: String? = null

        override suspend fun storeSecureToken(token: String): Result<Unit> {
            storedToken = token
            return Result.success(Unit)
        }
        override suspend fun getSecureToken() = storedToken?.let { Result.success(it) } ?: Result.failure(Exception("No token"))
        override suspend fun clearSecureToken(): Result<Unit> {
            storedToken = null
            return Result.success(Unit)
        }
    }
}
