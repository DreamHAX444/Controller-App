package com.livetracker.controller.core.tracker.domain

data class HeartbeatState(
    val lastHeartbeatTimestamp: Long? = null,
    val sequence: Int = 0,
    val missedHeartbeatCount: Int = 0,
    val health: HeartbeatHealth = HeartbeatHealth.UNKNOWN
)
