package com.livetracker.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livetracker.controller.auth.AuthenticationViewModel
import com.livetracker.controller.auth.LoadingScreen
import com.livetracker.controller.auth.LoginScreen
import com.livetracker.controller.auth.StatusScreen
import com.livetracker.controller.auth.pin.PinScreen
import com.livetracker.controller.auth.pin.PinViewModel
import com.livetracker.controller.core.auth.data.StubAuthenticationRepository
import com.livetracker.controller.core.auth.data.StubCredentialStore
import com.livetracker.controller.core.auth.data.StubSessionRepository
import com.livetracker.controller.core.auth.domain.usecase.AuthenticateUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.LogoutUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.RestoreSessionUseCase
import com.livetracker.controller.core.auth.domain.usecase.ValidateSessionUseCase
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import com.livetracker.controller.core.security.EncryptedPreferencesStorage
import com.livetracker.controller.core.security.PinManager
import com.livetracker.controller.main.MainShellScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Manual dependency injection for Phase 1.3
        val secureStorage = EncryptedPreferencesStorage(applicationContext)
        val pinManager = PinManager(secureStorage)
        
        // Stubs for Phase 1.3
        val credentialStore = StubCredentialStore()
        val sessionRepository = StubSessionRepository()
        val authRepository = StubAuthenticationRepository(sessionRepository, credentialStore)
        
        // Use cases
        val restoreSessionUseCase = RestoreSessionUseCase(credentialStore, authRepository)
        val authenticateUserUseCase = AuthenticateUserUseCase(authRepository)
        val validateSessionUseCase = ValidateSessionUseCase(sessionRepository)
        val logoutUserUseCase = LogoutUserUseCase(authRepository, credentialStore)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(PinViewModel::class.java)) {
                    return PinViewModel(pinManager) as T
                }
                if (modelClass.isAssignableFrom(AuthenticationViewModel::class.java)) {
                    return AuthenticationViewModel(
                        restoreSessionUseCase,
                        authenticateUserUseCase,
                        validateSessionUseCase,
                        logoutUserUseCase
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val authViewModel: AuthenticationViewModel = viewModel(factory = factory)
                    val authState by authViewModel.state.collectAsState()

                    when (val state = authState) {
                        is AuthenticationState.Initializing -> LoadingScreen()
                        
                        is AuthenticationState.Unauthenticated -> LoginScreen(
                            onLoginSubmit = { authViewModel.login(it) },
                            isLoading = false
                        )
                        
                        is AuthenticationState.Authenticating -> LoginScreen(
                            onLoginSubmit = {},
                            isLoading = true
                        )
                        
                        is AuthenticationState.Error -> LoginScreen(
                            onLoginSubmit = { authViewModel.login(it) },
                            isLoading = false,
                            errorMessage = state.message
                        )
                        
                        is AuthenticationState.SessionExpired -> StatusScreen(
                            title = "Session Expired",
                            message = "Your session has expired. Please log in again.",
                            actionText = "Log In",
                            onAction = { authViewModel.logout() }
                        )
                        
                        is AuthenticationState.AccountDisabled -> StatusScreen(
                            title = "Account Disabled",
                            message = "Your account has been disabled. Contact an administrator.",
                            actionText = "Back",
                            onAction = { authViewModel.logout() }
                        )
                        
                        is AuthenticationState.AccountSuspended -> StatusScreen(
                            title = "Account Suspended",
                            message = "Your account has been suspended.",
                            actionText = "Back",
                            onAction = { authViewModel.logout() }
                        )
                        
                        is AuthenticationState.AuthenticationUnavailable -> StatusScreen(
                            title = "Service Unavailable",
                            message = "Authentication services are currently unavailable.",
                            actionText = "Retry",
                            onAction = { authViewModel.logout() }
                        )
                        
                        is AuthenticationState.Authenticated -> {
                            // Phase 1.3: PIN Gate
                            var pinPassed by rememberSaveable { mutableStateOf(false) }
                            
                            if (pinPassed) {
                                MainShellScreen(
                                    onLogout = { 
                                        pinPassed = false
                                        authViewModel.logout() 
                                    }
                                )
                            } else {
                                val pinViewModel: PinViewModel = viewModel(factory = factory)
                                PinScreen(
                                    viewModel = pinViewModel,
                                    onAuthenticated = { pinPassed = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
