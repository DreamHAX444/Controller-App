package com.livetracker.controller.core.tracker.location.domain

class LocationFreshnessPolicy(
    private val staleThresholdMs: Long = 15_000L,
    private val offlineThresholdMs: Long = 60_000L,
    private val futureToleranceMs: Long = 5_000L
) {
    fun evaluate(locationTimestamp: Long, currentTimeMs: Long): LocationState {
        val age = currentTimeMs - locationTimestamp
        return when {
            age < -futureToleranceMs -> LocationState.UNKNOWN // Time anomaly beyond tolerance
            age < 0 -> LocationState.LIVE // Future within tolerance
            age <= staleThresholdMs -> LocationState.LIVE
            age <= offlineThresholdMs -> LocationState.STALE
            else -> LocationState.OFFLINE
        }
    }
}
