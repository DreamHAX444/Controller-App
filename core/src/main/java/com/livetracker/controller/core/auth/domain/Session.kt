package com.livetracker.controller.core.auth.domain

data class Session(
    val sessionId: String,
    val user: User,
    val isAuthenticated: Boolean,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun isValid(currentTime: Long): Boolean {
        return isAuthenticated && currentTime < expiresAt
    }
}
