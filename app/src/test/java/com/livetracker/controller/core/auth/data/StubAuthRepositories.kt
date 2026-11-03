package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.*
import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow

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
        "owner" to User("u0", "owner", Role.OWNER, AccountStatus.ACTIVE, emptyList()),
        "admin" to User("u1", "admin", Role.ADMIN, AccountStatus.ACTIVE, emptyList()),
        "user" to User(
            "u2",
            "user",
            Role.USER,
            AccountStatus.ACTIVE,
            listOf(
                DevicePermission(
                    deviceId = "Google sdk_gphone16k_x86_64 (A7A0)",
                    allowedServices = setOf(
                        ServicePermission.LOCATION,
                        ServicePermission.CAMERA,
                        ServicePermission.DEVICE_AUDIO,
                        ServicePermission.SCREEN_CAPTURE,
                        ServicePermission.FILE_READ,
                        ServicePermission.FILE_WRITE,
                        ServicePermission.FILE_TRANSFER
                    ),
                    allowedActions = setOf(Action.READ, Action.WRITE, Action.EXECUTE)
                ),
                DevicePermission(
                    deviceId = "Google sdk_gphone16k_x86_64 (E10B)",
                    allowedServices = setOf(
                        ServicePermission.LOCATION,
                        ServicePermission.CAMERA,
                        ServicePermission.DEVICE_AUDIO,
                        ServicePermission.SCREEN_CAPTURE,
                        ServicePermission.FILE_READ,
                        ServicePermission.FILE_WRITE,
                        ServicePermission.FILE_TRANSFER
                    ),
                    allowedActions = setOf(Action.READ, Action.WRITE, Action.EXECUTE)
                ),
                DevicePermission(
                    deviceId = "Google sdk_gphone16k_x86_64",
                    allowedServices = setOf(
                        ServicePermission.LOCATION,
                        ServicePermission.CAMERA,
                        ServicePermission.DEVICE_AUDIO,
                        ServicePermission.SCREEN_CAPTURE,
                        ServicePermission.FILE_READ,
                        ServicePermission.FILE_WRITE,
                        ServicePermission.FILE_TRANSFER
                    ),
                    allowedActions = setOf(Action.READ, Action.WRITE, Action.EXECUTE)
                )
            )
        ),
        "visitor" to User("u5", "visitor", Role.VISITOR, AccountStatus.ACTIVE, emptyList()),
        "disabled" to User("u3", "disabled", Role.USER, AccountStatus.DISABLED, emptyList()),
        "suspended" to User("u4", "suspended", Role.USER, AccountStatus.SUSPENDED, emptyList()),
        "pending" to User("u6", "pending", Role.USER, AccountStatus.PENDING, emptyList()),
        "unavailable" to User("u7", "unavailable", Role.USER, AccountStatus.UNKNOWN, emptyList())
    )

    override suspend fun authenticate(credentialIdentifier: String): AuthenticationResult {
        delay(500) // Simulate network delay
        val user = users[credentialIdentifier]
            ?: return AuthenticationResult.InvalidCredentials
            
        return when (user.accountStatus) {
            AccountStatus.DISABLED -> AuthenticationResult.AccountDisabled
            AccountStatus.SUSPENDED -> AuthenticationResult.AccountSuspended
            AccountStatus.UNKNOWN -> AuthenticationResult.AuthenticationUnavailable
            AccountStatus.PENDING -> AuthenticationResult.AccountPending
            AccountStatus.ACTIVE -> {
                val session = Session(
                    sessionId = "sess_${java.util.UUID.randomUUID()}",
                    user = user,
                    isAuthenticated = true,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + 24L * 3600_000L // 24 hours
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
    private val _activeSession = kotlinx.coroutines.flow.MutableStateFlow<Session?>(null)

    override fun observeActiveSession(): kotlinx.coroutines.flow.Flow<Session?> = _activeSession.asStateFlow()

    override suspend fun getActiveSession(): Result<Session> {
        return _activeSession.value?.let { Result.success(it) } ?: Result.failure(Exception("No active session"))
    }

    override suspend fun createSession(session: Session): Result<Unit> {
        _activeSession.value = session
        return Result.success(Unit)
    }

    override suspend fun updateSessionUser(user: User): Result<Unit> {
        val current = _activeSession.value ?: return Result.failure(IllegalStateException("No active session"))
        _activeSession.value = current.copy(user = user)
        return Result.success(Unit)
    }

    override suspend fun invalidateSession(): Result<Unit> {
        _activeSession.value = null
        return Result.success(Unit)
    }
}
