package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.AuthenticationResult
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.repository.FakeAuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.FakeCredentialStore
import com.livetracker.controller.core.auth.domain.repository.FakeSessionRepository
import com.livetracker.controller.core.auth.domain.repository.FakeUserRepository
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthUseCasesTest {

    private lateinit var userRepository: FakeUserRepository
    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var authRepository: FakeAuthenticationRepository
    private lateinit var credentialStore: FakeCredentialStore
    
    private var mockCurrentTime: Long = 1000L

    private lateinit var authenticateUserUseCase: AuthenticateUserUseCase
    private lateinit var logoutUserUseCase: LogoutUserUseCase
    private lateinit var validateSessionUseCase: ValidateSessionUseCase
    private lateinit var restoreSessionUseCase: RestoreSessionUseCase

    @Before
    fun setup() {
        userRepository = FakeUserRepository()
        sessionRepository = FakeSessionRepository()
        authRepository = FakeAuthenticationRepository(
            userRepository, 
            sessionRepository,
            currentTimeProvider = { mockCurrentTime }
        )
        credentialStore = FakeCredentialStore()

        authenticateUserUseCase = AuthenticateUserUseCase(authRepository)
        logoutUserUseCase = LogoutUserUseCase(authRepository, credentialStore)
        validateSessionUseCase = ValidateSessionUseCase(sessionRepository, currentTimeProvider = { mockCurrentTime })
        restoreSessionUseCase = RestoreSessionUseCase(credentialStore, authRepository)
    }

    @Test
    fun `successful authentication creates valid session`() = runBlocking {
        val activeUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(activeUser)

        val result = authenticateUserUseCase("user1")
        
        assertTrue(result is AuthenticationResult.Success)
        val session = (result as AuthenticationResult.Success).session
        assertEquals("user1", session.user.id)
        assertTrue(session.isAuthenticated)
        
        // Ensure session was stored in repo
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertEquals(session, repoSession)
    }

    @Test
    fun `invalid credentials do not create session`() = runBlocking {
        val result = authenticateUserUseCase("invalid")
        assertTrue(result is AuthenticationResult.InvalidCredentials)
        
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
    }

    @Test
    fun `disabled account cannot authenticate`() = runBlocking {
        val disabledUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.DISABLED, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(disabledUser)

        val result = authenticateUserUseCase("user1")
        assertTrue(result is AuthenticationResult.AccountDisabled)
        
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
    }

    @Test
    fun `suspended account cannot authenticate`() = runBlocking {
        val suspendedUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.SUSPENDED, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(suspendedUser)

        val result = authenticateUserUseCase("user1")
        assertTrue(result is AuthenticationResult.AccountSuspended)
        
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
    }

    @Test
    fun `validateSession returns session if valid`() = runBlocking {
        val activeUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(activeUser)
        
        authenticateUserUseCase("user1") // sets up the session
        
        val validSession = validateSessionUseCase()
        assertNotNull(validSession)
        assertEquals("user1", validSession?.user?.id)
    }

    @Test
    fun `validateSession returns null and invalidates if expired`() = runBlocking {
        val activeUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(activeUser)
        
        authenticateUserUseCase("user1") // sets up the session expiring in 1 hour
        
        mockCurrentTime += 3600000 + 1 // Advance time past expiration
        
        val expiredSession = validateSessionUseCase()
        assertNull(expiredSession)
        
        // Ensure active session was invalidated
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
    }

    @Test
    fun `logout invalidates session and clears credentials`() = runBlocking {
        val activeUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(activeUser)
        
        authenticateUserUseCase("user1")
        credentialStore.storeSecureToken("some-secure-token")
        
        val logoutResult = logoutUserUseCase()
        assertTrue(logoutResult.isSuccess)
        
        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
        
        val token = credentialStore.getSecureToken().getOrNull()
        assertNull(token)
    }

    @Test
    fun `restoreSession successfully restores with valid token`() = runBlocking {
        val activeUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(activeUser)
        
        credentialStore.storeSecureToken("user1") // In this mock, the token IS the userId
        
        val state = restoreSessionUseCase()
        assertTrue(state is AuthenticationState.Authenticated)
        val session = (state as AuthenticationState.Authenticated).session
        assertEquals("user1", session.user.id)
    }

    @Test
    fun `restoreSession returns unauthenticated with missing token`() = runBlocking {
        val state = restoreSessionUseCase()
        assertTrue(state is AuthenticationState.Unauthenticated)
    }

    @Test
    fun `restoreSession handles disabled account`() = runBlocking {
        val disabledUser = User(id = "user1", displayName = "Test", role = Role.USER, accountStatus = AccountStatus.DISABLED, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(disabledUser)
        
        credentialStore.storeSecureToken("user1") 
        
        val state = restoreSessionUseCase()
        assertTrue(state is AuthenticationState.AccountDisabled)
    }
}
