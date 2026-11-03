package com.livetracker.controller.core.auth.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AccountStatus {
    PENDING,
    ACTIVE,
    DISABLED,
    SUSPENDED,
    UNKNOWN
}

enum class Action {
    READ,
    WRITE,
    EXECUTE,
    DELETE,
    MANAGE
}

@Serializable
enum class Role {
    OWNER,
    ADMIN,
    USER,
    VISITOR
}

enum class ServicePermission {
    LOCATION,
    CAMERA,
    MICROPHONE_AUDIO,
    DEVICE_AUDIO,
    SCREEN_CAPTURE,
    FILE_READ,
    FILE_WRITE,
    FILE_DELETE,
    FILE_RENAME,
    FILE_TRANSFER
}

data class DevicePermission(
    val deviceId: String,
    val allowedServices: Set<ServicePermission>,
    val allowedActions: Set<Action> = setOf(Action.READ, Action.EXECUTE, Action.WRITE, Action.DELETE),
    val expiresAt: Long? = null
)
