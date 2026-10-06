package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.domain.Session

interface SessionRepository {
    suspend fun createSession(session: Session): Result<Unit>
    suspend fun getActiveSession(): Result<Session>
    suspend fun invalidateSession(): Result<Unit>
}
