package com.livetracker.controller.core.tracker.map.domain

import com.livetracker.controller.core.tracker.device.domain.DeviceSelection

sealed interface MapState {
    data object Initializing : MapState
    data object Empty : MapState
    
    data class Active(
        val selectionMode: DeviceSelection,
        val markers: List<MapMarker>,
        val lastUpdateTimestamp: Long = 0L
    ) : MapState
}
