package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface TrackerDeviceRepository {
    suspend fun registerDevice(device: TrackerDevice): Result<Unit>
    suspend fun unregisterDevice(deviceId: TrackerId): Result<Unit>
    suspend fun updateDevice(device: TrackerDevice): Result<Unit>
    
    /**
     * Atomically updates a device based on its current state.
     */
    suspend fun updateDevice(deviceId: TrackerId, modifier: (TrackerDevice) -> TrackerDevice): Result<Unit>
    
    suspend fun getDevice(deviceId: TrackerId): TrackerDevice?
    suspend fun getAllDevices(): List<TrackerDevice>
    
    fun observeDevice(deviceId: TrackerId): Flow<TrackerDevice?>
    fun observeAllDevices(): Flow<List<TrackerDevice>>
}
