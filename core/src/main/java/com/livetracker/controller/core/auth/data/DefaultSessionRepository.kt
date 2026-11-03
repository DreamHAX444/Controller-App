package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultSessionRepository : SessionRepository {

    private val _activeSession = MutableStateFlow<Session?>(null)

    override fun observeActiveSession(): Flow<Session?> = _activeSession.asStateFlow()

    override suspend fun getActiveSession(): Result<Session> {
        return _activeSession.value?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("No active session"))
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
