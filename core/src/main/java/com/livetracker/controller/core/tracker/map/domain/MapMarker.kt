package com.livetracker.controller.core.tracker.map.domain

import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.domain.LocationState

data class MapMarker(
    val deviceId: TrackerId,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val locationState: LocationState,
    val accuracy: Float?,
    val bearing: Float?,
    val speed: Float?,
    val timestamp: Long,
    val connectionState: ConnectionState,
    val healthState: DeviceHealth,
    val isSelected: Boolean
)
