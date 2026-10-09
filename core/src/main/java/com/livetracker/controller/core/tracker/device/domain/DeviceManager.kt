package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

interface DeviceManager {
    suspend fun registerDevice(deviceId: TrackerId, displayName: String)
    suspend fun unregisterDevice(deviceId: TrackerId)
    suspend fun enableDevice(deviceId: TrackerId)
    suspend fun disableDevice(deviceId: TrackerId)
    suspend fun connectDevice(deviceId: TrackerId)
    suspend fun disconnectDevice(deviceId: TrackerId)
}
