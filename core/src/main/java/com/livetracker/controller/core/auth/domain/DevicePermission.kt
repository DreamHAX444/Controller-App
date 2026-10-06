package com.livetracker.controller.core.auth.domain

data class DevicePermission(
    val deviceId: String,
    val allowedServices: Set<ServicePermission>
)
