package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.domain.*

// TEST INFRASTRUCTURE ONLY
class FakeUserRepository : UserRepository {
    private val users = mutableMapOf<String, User>()
    private var currentUserId: String? = null

    fun addUser(user: User) {
        users[user.id] = user
    }

    fun setCurrentUser(userId: String?) {
        currentUserId = userId
    }

    override suspend fun getUser(userId: String): Result<User> {
        val user = users[userId]
        return if (user != null) Result.success(user) else Result.failure(Exception("User not found"))
    }

    override suspend fun getCurrentUser(): Result<User> {
        val userId = currentUserId ?: return Result.failure(Exception("No current user"))
        return getUser(userId)
    }
}

class FakeSessionRepository : SessionRepository {
    private var activeSession: Session? = null

    override suspend fun createSession(session: Session): Result<Unit> {
        activeSession = session
        return Result.success(Unit)
    }

    override suspend fun getActiveSession(): Result<Session> {
        val session = activeSession
        return if (session != null) Result.success(session) else Result.failure(Exception("No active session"))
    }

    override suspend fun invalidateSession(): Result<Unit> {
        activeSession = null
        return Result.success(Unit)
    }
}

class FakeAuthenticationRepository(
    private val userRepository: FakeUserRepository,
    private val sessionRepository: FakeSessionRepository,
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() }
) : AuthenticationRepository {

    // A fake way to trigger different responses based on the string passed in
    override suspend fun authenticate(credentialIdentifier: String): AuthenticationResult {
        if (credentialIdentifier == "unavailable") return AuthenticationResult.AuthenticationUnavailable
        if (credentialIdentifier == "invalid") return AuthenticationResult.InvalidCredentials

        val userResult = userRepository.getUser(credentialIdentifier)
        if (userResult.isFailure) return AuthenticationResult.InvalidCredentials

        val user = userResult.getOrThrow()
        
        return when (user.accountStatus) {
            AccountStatus.DISABLED -> AuthenticationResult.AccountDisabled
            AccountStatus.SUSPENDED -> AuthenticationResult.AccountSuspended
            AccountStatus.UNKNOWN -> AuthenticationResult.InvalidCredentials
            AccountStatus.ACTIVE -> {
                val session = Session(
                    sessionId = "sess-${System.currentTimeMillis()}",
                    user = user,
                    isAuthenticated = true,
                    createdAt = currentTimeProvider(),
                    expiresAt = currentTimeProvider() + 3600000 // 1 hour
                )
                sessionRepository.createSession(session)
                AuthenticationResult.Success(session)
            }
        }
    }

    override suspend fun logout(): Result<Unit> {
        return sessionRepository.invalidateSession()
    }
}

class FakeCredentialStore : CredentialStore {
    private var storedToken: String? = null

    override suspend fun storeSecureToken(token: String): Result<Unit> {
        storedToken = token
        return Result.success(Unit)
    }

    override suspend fun getSecureToken(): Result<String> {
        return storedToken?.let { Result.success(it) } ?: Result.failure(Exception("No token"))
    }

    override suspend fun clearSecureToken(): Result<Unit> {
        storedToken = null
        return Result.success(Unit)
    }
}
