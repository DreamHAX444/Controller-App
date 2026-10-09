package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ObserveDeviceSummariesUseCase(
    private val deviceRepository: TrackerDeviceRepository,
    private val locationRepository: TrackerLocationRepository
) {
    operator fun invoke(): Flow<List<TrackerDeviceSummary>> {
        return combine(
            deviceRepository.observeAllDevices(),
            locationRepository.getAllLocations()
        ) { devices, locations ->
            val locationMap = locations.associateBy { it.deviceId }
            devices.map { device ->
                val locationRecord = locationMap[device.deviceId]
                TrackerDeviceSummary(
                    device = device,
                    latestLocation = locationRecord,
                    locationState = locationRecord?.state ?: LocationState.UNKNOWN
                )
            }
        }
    }
}
