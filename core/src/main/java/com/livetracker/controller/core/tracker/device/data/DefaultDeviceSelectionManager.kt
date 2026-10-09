package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultDeviceSelectionManager : DeviceSelectionManager {
    private val _selection = MutableStateFlow<DeviceSelection>(DeviceSelection.Empty)
    
    override fun getSelection(): StateFlow<DeviceSelection> = _selection.asStateFlow()

    override fun selectDevice(deviceId: TrackerId) {
        _selection.value = DeviceSelection.Single(deviceId)
    }

    override fun selectMultipleDevices(deviceIds: Set<TrackerId>) {
        when {
            deviceIds.isEmpty() -> _selection.value = DeviceSelection.Empty
            deviceIds.size == 1 -> _selection.value = DeviceSelection.Single(deviceIds.first())
            else -> _selection.value = DeviceSelection.Multiple(deviceIds)
        }
    }

    override fun selectAll() {
        _selection.value = DeviceSelection.All
    }

    override fun clearSelection() {
        _selection.value = DeviceSelection.Empty
    }
}
