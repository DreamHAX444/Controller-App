package com.livetracker.controller.core.tracker.location.domain

import com.livetracker.controller.core.tracker.domain.TrackerEvent

class ProcessLocationUpdateUseCase(
    private val repository: TrackerLocationRepository,
    private val policy: LocationFreshnessPolicy = LocationFreshnessPolicy()
) {
    suspend operator fun invoke(event: TrackerEvent.LocationUpdated, currentTimeMs: Long = System.currentTimeMillis()) {
        if (event.deviceId.value.isEmpty()) return
        if (event.latitude !in -90.0..90.0) return
        if (event.longitude !in -180.0..180.0) return
        if (event.accuracy < 0f) return
        if (event.speed != null && event.speed < 0f) return

        val state = policy.evaluate(event.timestamp, currentTimeMs)
        if (state == LocationState.UNKNOWN) return

        val location = Location(
            deviceId = event.deviceId,
            latitude = event.latitude,
            longitude = event.longitude,
            accuracy = event.accuracy,
            bearing = event.bearing,
            speed = event.speed,
            timestamp = event.timestamp,
            state = state
        )
        repository.updateLocation(location)
    }
}
