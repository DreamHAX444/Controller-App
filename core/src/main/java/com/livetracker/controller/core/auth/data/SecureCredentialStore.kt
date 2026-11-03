package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.security.SecureStorage

class SecureCredentialStore(private val storage: SecureStorage) : CredentialStore {

    override suspend fun storeSecureToken(token: String): Result<Unit> = runCatching {
        storage.putString(KEY_AUTH_TOKEN, token)
    }

    override suspend fun getSecureToken(): Result<String> = runCatching {
        storage.getString(KEY_AUTH_TOKEN)?.takeIf { it.isNotBlank() }
            ?: throw NoSuchElementException("No secure token stored")
    }

    override suspend fun clearSecureToken(): Result<Unit> = runCatching {
        storage.putString(KEY_AUTH_TOKEN, null)
    }

    companion object {
        private const val KEY_AUTH_TOKEN = "trace_secure_auth_token"
    }
}
