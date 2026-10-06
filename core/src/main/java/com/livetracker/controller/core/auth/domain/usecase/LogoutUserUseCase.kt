package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore

class LogoutUserUseCase(
    private val authRepository: AuthenticationRepository,
    private val credentialStore: CredentialStore
) {
    suspend operator fun invoke(): Result<Unit> {
        // Clear secure token storage so session can't be restored
        credentialStore.clearSecureToken()
        return authRepository.logout()
    }
}
