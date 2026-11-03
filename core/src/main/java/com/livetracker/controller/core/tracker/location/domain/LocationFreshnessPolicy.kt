package com.livetracker.controller.core.tracker.location.domain

class LocationFreshnessPolicy(
    val staleThresholdMs: Long = 15_000L,
    val offlineThresholdMs: Long = 60_000L,
    val futureToleranceMs: Long = 300_000L
) {
    fun evaluate(locationTimestamp: Long, currentTimeMs: Long): LocationState {
        if (locationTimestamp <= 0L || currentTimeMs <= 0L) return LocationState.UNKNOWN
        val age = currentTimeMs - locationTimestamp
        return when {
            age < -futureToleranceMs -> LocationState.UNKNOWN // Time anomaly beyond tolerance
            age < 0 -> LocationState.LIVE // Future within tolerance
            age <= staleThresholdMs -> LocationState.LIVE
            age <= offlineThresholdMs -> LocationState.STALE
            else -> LocationState.OFFLINE
        }
    }

    fun isLive(locationTimestamp: Long, currentTimeMs: Long): Boolean =
        evaluate(locationTimestamp, currentTimeMs) == LocationState.LIVE

    fun isStale(locationTimestamp: Long, currentTimeMs: Long): Boolean =
        evaluate(locationTimestamp, currentTimeMs) == LocationState.STALE

    fun isOffline(locationTimestamp: Long, currentTimeMs: Long): Boolean =
        evaluate(locationTimestamp, currentTimeMs) == LocationState.OFFLINE
}

/**
 * High-performance, exact elapsed-time formatter.
 * Formats timestamps into live, swift-updating relative time (e.g. 0s, 1s, 25s, 1m 5s, 2h 10m).
 * Eliminates generic "Just now" so every single elapsed second is directly visible and live.
 */
fun formatElapsedSwift(timestamp: Long?, now: Long = System.currentTimeMillis()): String {
    if (timestamp == null || timestamp <= 0L) return "Never"
    val diffMs = (now - timestamp).coerceAtLeast(0L)
    val totalSeconds = diffMs / 1000
    val minutes = totalSeconds / 60
    val remSeconds = totalSeconds % 60
    val hours = minutes / 60
    val remMinutes = minutes % 60
    val days = hours / 24
    val remHours = hours % 24

    return when {
        totalSeconds < 60 -> "${totalSeconds}s"
        minutes < 60 -> "${minutes}m ${remSeconds}s"
        hours < 24 -> "${hours}h ${remMinutes}m"
        else -> "${days}d ${remHours}h"
    }
}
