package com.livetracker.controller.core.auth.domain

import com.livetracker.controller.core.auth.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthenticationIntegrationTest {

    private lateinit var userRepository: FakeUserRepository
    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var authRepository: AuthenticationRepository
    private lateinit var authEngine: AuthorizationEngine
    private lateinit var commandExecutor: CommandExecutor

    private val device1 = "dev-1"
    private val currentTime = 1000L

    @Before
    fun setup() {
        userRepository = FakeUserRepository()
        sessionRepository = FakeSessionRepository()
        authRepository = FakeAuthenticationRepository(userRepository, sessionRepository) { currentTime }
        authEngine = DefaultAuthorizationEngine()
        commandExecutor = CommandExecutor(authEngine)
    }

    private fun seedUser(id: String, status: AccountStatus): User {
        val user = User(
            id = id,
            displayName = "User $id",
            role = Role.USER,
            accountStatus = status,
            devicePermissions = listOf(
                DevicePermission(device1, setOf(ServicePermission.LOCATION, ServicePermission.CAMERA))
            )
        )
        userRepository.addUser(user)
        return user
    }

    @Test
    fun `successful authentication creates active session and allows valid commands`() = runBlocking {
        seedUser("active-user", AccountStatus.ACTIVE)

        val result = authRepository.authenticate("active-user")
        assertTrue(result is AuthenticationResult.Success)

        val session = (result as AuthenticationResult.Success).session
        
        val authResult = authEngine.authorize(session, device1, ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.Allowed, authResult)
    }

    @Test
    fun `disabled account returns AccountDisabled and blocks authentication`() = runBlocking {
        seedUser("disabled-user", AccountStatus.DISABLED)

        val result = authRepository.authenticate("disabled-user")
        assertEquals(AuthenticationResult.AccountDisabled, result)
        
        val sessionResult = sessionRepository.getActiveSession()
        assertTrue(sessionResult.isFailure)
    }

    @Test
    fun `suspended account returns AccountSuspended and blocks authentication`() = runBlocking {
        seedUser("suspended-user", AccountStatus.SUSPENDED)

        val result = authRepository.authenticate("suspended-user")
        assertEquals(AuthenticationResult.AccountSuspended, result)
    }

    @Test
    fun `active session with user transitioning to disabled is caught by AuthorizationEngine`() = runBlocking {
        // Authenticate when active
        val user = seedUser("mutating-user", AccountStatus.ACTIVE)
        val authResult = authRepository.authenticate("mutating-user")
        val session = (authResult as AuthenticationResult.Success).session

        // Simulate backend pushing an account disabled state to the cached session user
        val mutatedSession = session.copy(
            user = session.user.copy(accountStatus = AccountStatus.DISABLED)
        )

        // Engine must reject
        val engineResult = authEngine.authorize(mutatedSession, device1, ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.AccountNotActive, engineResult)
    }

    @Test
    fun `logout invalidates session`() = runBlocking {
        seedUser("active-user", AccountStatus.ACTIVE)
        authRepository.authenticate("active-user")
        
        assertTrue(sessionRepository.getActiveSession().isSuccess)
        
        authRepository.logout()
        
        assertTrue(sessionRepository.getActiveSession().isFailure)
    }
}
