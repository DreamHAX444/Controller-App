package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

sealed interface DeviceSelection {
    data object Empty : DeviceSelection
    data object All : DeviceSelection
    data class Single(val deviceId: TrackerId) : DeviceSelection
    data class Multiple(val deviceIds: Set<TrackerId>) : DeviceSelection
}
