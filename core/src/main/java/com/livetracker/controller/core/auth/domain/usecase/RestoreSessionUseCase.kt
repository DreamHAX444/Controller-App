package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.AuthenticationResult
import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.auth.presentation.AuthenticationState

class RestoreSessionUseCase(
    private val credentialStore: CredentialStore,
    private val authRepository: AuthenticationRepository
) {
    /**
     * Attempts to seamlessly restore an authenticated session using safely persisted credentials.
     */
    suspend operator fun invoke(): AuthenticationState {
        val tokenResult = credentialStore.getSecureToken()
        if (tokenResult.isFailure || tokenResult.getOrNull()?.isBlank() == true) {
            return AuthenticationState.Unauthenticated
        }

        val token = tokenResult.getOrThrow()
        
        // Attempt to re-authenticate with the secure token.
        // In reality, the repo might validate the token against the backend or locally.
        val result = authRepository.authenticate(token)
        
        return when (result) {
            is AuthenticationResult.Success -> AuthenticationState.Authenticated(result.session)
            is AuthenticationResult.AccountDisabled -> AuthenticationState.AccountDisabled
            is AuthenticationResult.AccountSuspended -> AuthenticationState.AccountSuspended
            is AuthenticationResult.SessionExpired -> AuthenticationState.SessionExpired
            is AuthenticationResult.AuthenticationUnavailable -> AuthenticationState.AuthenticationUnavailable
            is AuthenticationResult.InvalidCredentials, 
            is AuthenticationResult.InvalidRequest -> AuthenticationState.Unauthenticated
        }
    }
}
