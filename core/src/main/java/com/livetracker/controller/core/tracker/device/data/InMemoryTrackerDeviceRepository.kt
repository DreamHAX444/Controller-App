package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryTrackerDeviceRepository : TrackerDeviceRepository {

    private val devicesFlow = MutableStateFlow<Map<TrackerId, TrackerDevice>>(emptyMap())
    private val mutex = Mutex()

    override suspend fun registerDevice(device: TrackerDevice): Result<Unit> = mutex.withLock {
        val current = devicesFlow.value
        if (current.containsKey(device.deviceId)) {
            return Result.failure(IllegalStateException("Device already registered"))
        }
        devicesFlow.value = current + (device.deviceId to device)
        return Result.success(Unit)
    }

    override suspend fun unregisterDevice(deviceId: TrackerId): Result<Unit> = mutex.withLock {
        val current = devicesFlow.value
        if (!current.containsKey(deviceId)) {
            return Result.failure(IllegalArgumentException("Device not found"))
        }
        devicesFlow.value = current - deviceId
        return Result.success(Unit)
    }

    override suspend fun updateDevice(device: TrackerDevice): Result<Unit> = mutex.withLock {
        val current = devicesFlow.value
        if (!current.containsKey(device.deviceId)) {
            return Result.failure(IllegalArgumentException("Device not found"))
        }
        devicesFlow.value = current + (device.deviceId to device)
        return Result.success(Unit)
    }

    override suspend fun updateDevice(
        deviceId: TrackerId,
        modifier: (TrackerDevice) -> TrackerDevice
    ): Result<Unit> = mutex.withLock {
        val current = devicesFlow.value
        val device = current[deviceId] ?: return Result.failure(IllegalArgumentException("Device not found"))
        val updated = modifier(device)
        if (updated.deviceId != deviceId) {
             return Result.failure(IllegalArgumentException("Modifier cannot change device ID"))
        }
        devicesFlow.value = current + (deviceId to updated)
        return Result.success(Unit)
    }

    override suspend fun getDevice(deviceId: TrackerId): TrackerDevice? {
        return devicesFlow.value[deviceId]
    }

    override suspend fun getAllDevices(): List<TrackerDevice> {
        return devicesFlow.value.values.toList()
    }

    override fun observeDevice(deviceId: TrackerId): Flow<TrackerDevice?> {
        return devicesFlow.map { it[deviceId] }
    }

    override fun observeAllDevices(): Flow<List<TrackerDevice>> {
        return devicesFlow.map { it.values.toList() }
    }
}
