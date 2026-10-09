package com.livetracker.controller.core.tracker.location.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class LocationState {
    LIVE,
    STALE,
    OFFLINE,
    UNKNOWN
}

data class Location(
    val deviceId: TrackerId,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val bearing: Float?,
    val speed: Float?,
    val timestamp: Long,
    val state: LocationState
)
