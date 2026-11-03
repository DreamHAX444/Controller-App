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

    @Test
    fun `pending account returns AccountPending and blocks session creation`() = runBlocking {
        val pendingUser = User(id = "pending-1", displayName = "Pending", role = Role.USER, accountStatus = AccountStatus.PENDING, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(pendingUser)

        val result = authenticateUserUseCase("pending-1")
        assertEquals(AuthenticationResult.AccountPending, result)

        val repoSession = sessionRepository.getActiveSession().getOrNull()
        assertNull(repoSession)
    }

    @Test
    fun `authentication across all roles returns Success with corresponding role`() = runBlocking {
        val roles = listOf(Role.OWNER, Role.ADMIN, Role.USER, Role.VISITOR)
        for (r in roles) {
            val uId = "user-${r.name.lowercase()}"
            val user = User(id = uId, displayName = "Name ${r.name}", role = r, accountStatus = AccountStatus.ACTIVE, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
            userRepository.addUser(user)

            val result = authenticateUserUseCase(uId)
            assertTrue(result is AuthenticationResult.Success)
            val session = (result as AuthenticationResult.Success).session
            assertEquals(r, session.user.role)
            assertEquals(uId, session.user.id)
            assertTrue(session.isAuthenticated)
        }
    }

    @Test
    fun `restoreSession handles pending account`() = runBlocking {
        val pendingUser = User(id = "pending-user", displayName = "Pending", role = Role.USER, accountStatus = AccountStatus.PENDING, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(pendingUser)
        credentialStore.storeSecureToken("pending-user")

        val state = restoreSessionUseCase()
        assertEquals(AuthenticationState.AccountPending, state)
    }

    @Test
    fun `restoreSession handles suspended account`() = runBlocking {
        val suspendedUser = User(id = "suspended-user", displayName = "Suspended", role = Role.USER, accountStatus = AccountStatus.SUSPENDED, devicePermissions = emptyList(), createdAt = mockCurrentTime, updatedAt = mockCurrentTime)
        userRepository.addUser(suspendedUser)
        credentialStore.storeSecureToken("suspended-user")

        val state = restoreSessionUseCase()
        assertEquals(AuthenticationState.AccountSuspended, state)
    }

    @Test
    fun `restoreSession handles unavailable service`() = runBlocking {
        credentialStore.storeSecureToken("unavailable")

        val state = restoreSessionUseCase()
        assertEquals(AuthenticationState.AuthenticationUnavailable, state)
    }
}
