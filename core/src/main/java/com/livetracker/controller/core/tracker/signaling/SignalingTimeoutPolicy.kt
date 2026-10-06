package com.livetracker.controller.core.tracker.signaling

data class SignalingTimeoutPolicy(
    val connectionTimeoutMs: Long = 10_000,
    val negotiationTimeoutMs: Long = 15_000,
    val messageTimeoutMs: Long = 5_000
)
