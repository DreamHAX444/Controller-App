package com.livetracker.controller.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.AuthenticationResult
import com.livetracker.controller.core.auth.domain.usecase.AuthenticateUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.LogoutUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.RestoreSessionUseCase
import com.livetracker.controller.core.auth.domain.usecase.ValidateSessionUseCase
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthenticationViewModel(
    private val restoreSessionUseCase: RestoreSessionUseCase,
    private val authenticateUserUseCase: AuthenticateUserUseCase,
    private val validateSessionUseCase: ValidateSessionUseCase,
    private val logoutUserUseCase: LogoutUserUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<AuthenticationState>(AuthenticationState.Initializing)
    val state: StateFlow<AuthenticationState> = _state.asStateFlow()

    init {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            _state.value = AuthenticationState.Initializing
            _state.value = restoreSessionUseCase()
        }
    }

    fun login(credentialIdentifier: String) {
        viewModelScope.launch {
            _state.value = AuthenticationState.Authenticating
            val result = authenticateUserUseCase(credentialIdentifier)
            _state.value = when (result) {
                is AuthenticationResult.Success -> AuthenticationState.Authenticated(result.session)
                is AuthenticationResult.AccountDisabled -> AuthenticationState.AccountDisabled
                is AuthenticationResult.AccountSuspended -> AuthenticationState.AccountSuspended
                is AuthenticationResult.SessionExpired -> AuthenticationState.SessionExpired
                is AuthenticationResult.AuthenticationUnavailable -> AuthenticationState.AuthenticationUnavailable
                is AuthenticationResult.InvalidCredentials -> AuthenticationState.Error("Invalid credentials")
                is AuthenticationResult.InvalidRequest -> AuthenticationState.Error("Invalid request")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            logoutUserUseCase()
            _state.value = AuthenticationState.Unauthenticated
        }
    }

    fun validateSession() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is AuthenticationState.Authenticated) {
                val validSession = validateSessionUseCase()
                if (validSession == null) {
                    _state.value = AuthenticationState.SessionExpired
                }
            }
        }
    }
}
