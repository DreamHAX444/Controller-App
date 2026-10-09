package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState

data class TrackerDeviceSummary(
    val device: TrackerDevice,
    val latestLocation: Location?,
    val locationState: LocationState
) {
    val isStale: Boolean
        get() = locationState == LocationState.STALE

    val locationTimestamp: Long?
        get() = latestLocation?.timestamp
}
