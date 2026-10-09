package com.livetracker.controller.core.tracker.recovery

data class ReconnectPolicy(
    val initialDelayMs: Long = 1000L,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 30000L,
    val maxAttempts: Int = 10
) {
    fun calculateDelay(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMs
        val delay = (initialDelayMs * Math.pow(multiplier, (attempt - 1).toDouble())).toLong()
        return delay.coerceAtMost(maxDelayMs)
    }
}
