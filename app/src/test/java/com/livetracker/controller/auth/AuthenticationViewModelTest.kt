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
}
