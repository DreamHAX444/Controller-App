package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.StateFlow

interface DeviceSelectionManager {
    fun getSelection(): StateFlow<DeviceSelection>
    fun selectDevice(deviceId: TrackerId)
    fun selectMultipleDevices(deviceIds: Set<TrackerId>)
    fun selectAll()
    fun clearSelection()
}
