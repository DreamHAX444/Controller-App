package com.livetracker.controller.core.auth.domain

data class User(
    val id: String,
    val displayName: String,
    val role: Role,
    val accountStatus: AccountStatus,
    val devicePermissions: List<DevicePermission>,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
