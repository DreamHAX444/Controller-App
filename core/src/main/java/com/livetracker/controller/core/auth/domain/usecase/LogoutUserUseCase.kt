package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth

class LogoutUserUseCase(
    private val authRepository: AuthenticationRepository,
    private val credentialStore: CredentialStore
) {
    suspend operator fun invoke(): Result<Unit> {
        // Clear secure token storage so session can't be restored
        credentialStore.clearSecureToken()
        runCatching {
            SupabaseClientProvider.client.auth.signOut()
        }
        return authRepository.logout()
    }
}
