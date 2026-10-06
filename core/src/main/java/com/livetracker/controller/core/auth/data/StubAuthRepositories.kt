package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.*
import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import kotlinx.coroutines.delay

class StubCredentialStore : CredentialStore {
    private var token: String? = null

    override suspend fun storeSecureToken(token: String): Result<Unit> {
        this.token = token
        return Result.success(Unit)
    }

    override suspend fun getSecureToken(): Result<String> {
        return token?.let { Result.success(it) } ?: Result.failure(Exception("No token"))
    }

    override suspend fun clearSecureToken(): Result<Unit> {
        token = null
        return Result.success(Unit)
    }
}

class StubAuthenticationRepository(
    private val sessionRepository: SessionRepository,
    private val credentialStore: CredentialStore
) : AuthenticationRepository {
    
    // In-memory users for testing
    private val users = mapOf(
        "admin" to User("u1", "admin", Role.ADMIN, AccountStatus.ACTIVE, emptyList()),
        "user" to User("u2", "user", Role.USER, AccountStatus.ACTIVE, emptyList()),
        "disabled" to User("u3", "disabled", Role.USER, AccountStatus.DISABLED, emptyList()),
        "suspended" to User("u4", "suspended", Role.USER, AccountStatus.SUSPENDED, emptyList())
    )

    override suspend fun authenticate(credentialIdentifier: String): AuthenticationResult {
        delay(500) // Simulate network delay
        val user = users[credentialIdentifier]
            ?: return AuthenticationResult.InvalidCredentials
            
        return when (user.accountStatus) {
            AccountStatus.DISABLED -> AuthenticationResult.AccountDisabled
            AccountStatus.SUSPENDED -> AuthenticationResult.AccountSuspended
            AccountStatus.UNKNOWN -> AuthenticationResult.AuthenticationUnavailable
            AccountStatus.ACTIVE -> {
                val session = Session(
                    sessionId = "sess_${System.currentTimeMillis()}",
                    user = user,
                    isAuthenticated = true,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 3600_000
                )
                sessionRepository.createSession(session)
                credentialStore.storeSecureToken(credentialIdentifier)
                AuthenticationResult.Success(session)
            }
        }
    }
    
    override suspend fun logout(): Result<Unit> {
        sessionRepository.invalidateSession()
        credentialStore.clearSecureToken()
        return Result.success(Unit)
    }
}

class StubSessionRepository : SessionRepository {
    private var activeSession: Session? = null

    override suspend fun getActiveSession(): Result<Session> {
        return activeSession?.let { Result.success(it) } ?: Result.failure(Exception("No active session"))
    }

    override suspend fun createSession(session: Session): Result<Unit> {
        activeSession = session
        return Result.success(Unit)
    }

    override suspend fun invalidateSession(): Result<Unit> {
        activeSession = null
        return Result.success(Unit)
    }
}
