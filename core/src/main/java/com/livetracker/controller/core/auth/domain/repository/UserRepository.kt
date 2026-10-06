package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.domain.User

interface UserRepository {
    suspend fun getUser(userId: String): Result<User>
    suspend fun getCurrentUser(): Result<User>
}
