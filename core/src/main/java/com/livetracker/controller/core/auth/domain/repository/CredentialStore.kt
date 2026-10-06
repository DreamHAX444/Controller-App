package com.livetracker.controller.core.auth.domain.repository

/**
 * Strict boundary for credential management.
 * The domain layer must NEVER receive or persist raw passwords, access tokens,
 * refresh tokens, or private keys directly.
 * 
 * Any implementation of this store should use Android Keystore-backed mechanisms
 * (e.g. EncryptedSharedPreferences).
 */
interface CredentialStore {
    suspend fun storeSecureToken(token: String): Result<Unit>
    suspend fun getSecureToken(): Result<String>
    suspend fun clearSecureToken(): Result<Unit>
}
