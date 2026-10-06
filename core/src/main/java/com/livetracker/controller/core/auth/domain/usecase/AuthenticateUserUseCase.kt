package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.AuthenticationResult
import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository

class AuthenticateUserUseCase(
    private val authRepository: AuthenticationRepository
) {
    suspend operator fun invoke(credentialIdentifier: String): AuthenticationResult {
        return authRepository.authenticate(credentialIdentifier)
    }
}
