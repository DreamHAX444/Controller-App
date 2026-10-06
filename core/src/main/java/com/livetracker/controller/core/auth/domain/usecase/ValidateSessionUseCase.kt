package com.livetracker.controller.core.auth.domain.usecase

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.SessionRepository

class ValidateSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    /**
     * Retrieves the active session and ensures it is not expired.
     * If expired, it triggers invalidation and returns null.
     */
    suspend operator fun invoke(): Session? {
        val result = sessionRepository.getActiveSession()
        if (result.isSuccess) {
            val session = result.getOrThrow()
            if (session.isValid(currentTimeProvider())) {
                return session
            } else {
                // Session is expired, invalidate it actively
                sessionRepository.invalidateSession()
            }
        }
        return null
    }
}
