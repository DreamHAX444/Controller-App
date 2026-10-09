package com.livetracker.controller.core.tracker.device.domain

sealed interface DeviceListState {
    data object Loading : DeviceListState
    data object Empty : DeviceListState
    data class Loaded(val devices: List<TrackerDevice>) : DeviceListState
    data class Error(val message: String) : DeviceListState
}
