package com.livetracker.controller.core.tracker.domain

data class HeartbeatPolicy(
    val expectedIntervalMs: Long = 15_000L,
    val degradedThresholdMs: Long = 45_000L, // 3 missed heartbeats
    val lostThresholdMs: Long = 90_000L      // 6 missed heartbeats
)
