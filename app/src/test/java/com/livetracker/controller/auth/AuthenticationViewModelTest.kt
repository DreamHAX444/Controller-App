package com.livetracker.controller.auth

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import com.livetracker.controller.core.auth.data.StubAuthenticationRepository
import com.livetracker.controller.core.auth.data.StubCredentialStore
import com.livetracker.controller.core.auth.data.StubSessionRepository
import com.livetracker.controller.core.auth.domain.usecase.AuthenticateUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.LogoutUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.RestoreSessionUseCase
import com.livetracker.controller.core.auth.domain.usecase.ValidateSessionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var credentialStore: StubCredentialStore
    private lateinit var sessionRepository: StubSessionRepository
    private lateinit var authRepository: StubAuthenticationRepository
    private lateinit var viewModel: AuthenticationViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        credentialStore = StubCredentialStore()
        sessionRepository = StubSessionRepository()
        authRepository = StubAuthenticationRepository(sessionRepository, credentialStore)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = AuthenticationViewModel(
            RestoreSessionUseCase(credentialStore, authRepository),
            AuthenticateUserUseCase(authRepository),
            ValidateSessionUseCase(sessionRepository),
            LogoutUserUseCase(authRepository, credentialStore)
        )
    }

    @Test
    fun `startup without session goes to unauthenticated`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(AuthenticationState.Unauthenticated, viewModel.state.value)
    }

    @Test
    fun `startup with valid session restores to authenticated`() = runTest(testDispatcher) {
        // Pre-populate stub
        val token = "admin"
        credentialStore.storeSecureToken(token)
        
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue(viewModel.state.value is AuthenticationState.Authenticated)
    }

    @Test
    fun `disabled account cannot reach authenticated shell on startup`() = runTest(testDispatcher) {
        credentialStore.storeSecureToken("disabled")
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(AuthenticationState.AccountDisabled, viewModel.state.value)
    }

    @Test
    fun `suspended account cannot reach authenticated shell on startup`() = runTest(testDispatcher) {
        credentialStore.storeSecureToken("suspended")
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(AuthenticationState.AccountSuspended, viewModel.state.value)
    }

    @Test
    fun `login success sets authenticated state`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.login("admin")
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue(viewModel.state.value is AuthenticationState.Authenticated)
    }

    @Test
    fun `login failure remains unauthenticated`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.login("wrong_user")
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue(viewModel.state.value is AuthenticationState.Error)
    }

    @Test
    fun `logout clears session and sets unauthenticated`() = runTest(testDispatcher) {
        createViewModel()
        viewModel.login("admin")
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.logout()
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(AuthenticationState.Unauthenticated, viewModel.state.value)
        val token = credentialStore.getSecureToken().getOrNull()
        assertTrue(token == null)
    }

    @Test
    fun `login with OWNER role sets authenticated state with OWNER profile`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("owner")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthenticationState.Authenticated)
        assertEquals(Role.OWNER, (state as AuthenticationState.Authenticated).session.user.role)
    }

    @Test
    fun `login with USER role sets authenticated state with USER profile`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("user")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthenticationState.Authenticated)
        assertEquals(Role.USER, (state as AuthenticationState.Authenticated).session.user.role)
    }

    @Test
    fun `login with VISITOR role sets authenticated state with VISITOR profile`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("visitor")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthenticationState.Authenticated)
        assertEquals(Role.VISITOR, (state as AuthenticationState.Authenticated).session.user.role)
    }

    @Test
    fun `login with pending account sets AccountPending state`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("pending")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.AccountPending, viewModel.state.value)
    }

    @Test
    fun `startup with pending account restores to AccountPending state`() = runTest(testDispatcher) {
        credentialStore.storeSecureToken("pending")
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.AccountPending, viewModel.state.value)
    }

    @Test
    fun `login with suspended account sets AccountSuspended state`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("suspended")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.AccountSuspended, viewModel.state.value)
    }

    @Test
    fun `login with disabled account sets AccountDisabled state`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("disabled")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.AccountDisabled, viewModel.state.value)
    }

    @Test
    fun `user cancellation during login returns to unauthenticated cleanly without crashing`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("admin")
        assertEquals(AuthenticationState.Authenticating, viewModel.state.value)

        viewModel.cancelLogin()
        assertEquals(AuthenticationState.Unauthenticated, viewModel.state.value)
    }

    @Test
    fun `network error during login transitions to AuthenticationUnavailable state`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("unavailable")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.AuthenticationUnavailable, viewModel.state.value)
    }

    @Test
    fun `concurrency - rapid multiple sign-in taps prevented while authenticating`() = runTest(testDispatcher) {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.login("admin")
        assertEquals(AuthenticationState.Authenticating, viewModel.state.value)

        // Rapid second tap for a different user while still authenticating
        viewModel.login("user")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verifies that first request completed and second tap was dropped
        val state = viewModel.state.value
        assertTrue(state is AuthenticationState.Authenticated)
        assertEquals("u1", (state as AuthenticationState.Authenticated).session.user.id)
    }

    @Test
    fun `validateSession with expired session transitions authenticated to SessionExpired`() = runTest(testDispatcher) {
        createViewModel()
        viewModel.login("admin")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is AuthenticationState.Authenticated)

        // Simulate session expiration / external invalidation in repository
        sessionRepository.invalidateSession()

        viewModel.validateSession()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthenticationState.SessionExpired, viewModel.state.value)
    }

    @Test
    fun `validateSession with valid session maintains Authenticated state`() = runTest(testDispatcher) {
        createViewModel()
        viewModel.login("admin")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is AuthenticationState.Authenticated)

        viewModel.validateSession()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthenticationState.Authenticated)
    }
}
