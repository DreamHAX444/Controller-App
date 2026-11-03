package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun createSession(session: Session): Result<Unit>
    suspend fun getActiveSession(): Result<Session>
    suspend fun updateSessionUser(user: User): Result<Unit>
    suspend fun invalidateSession(): Result<Unit>
    fun observeActiveSession(): Flow<Session?>
}
