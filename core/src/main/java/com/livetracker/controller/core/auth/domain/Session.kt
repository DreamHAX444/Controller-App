package com.livetracker.controller.core.auth.domain

data class Session(
    val sessionId: String,
    val user: User,
    val isAuthenticated: Boolean,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun isValid(currentTime: Long = System.currentTimeMillis()): Boolean {
        return isAuthenticated && currentTime < expiresAt
    }
}
