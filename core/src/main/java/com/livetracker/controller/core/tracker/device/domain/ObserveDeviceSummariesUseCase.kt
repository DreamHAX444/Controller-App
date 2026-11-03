package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class ObserveDeviceSummariesUseCase(
    private val deviceRepository: TrackerDeviceRepository,
    private val locationRepository: TrackerLocationRepository,
    private val sessionRepository: SessionRepository? = null
) {
    companion object {
        val DEMO_SUMMARIES: List<TrackerDeviceSummary> by lazy {
            val now = System.currentTimeMillis()
            listOf(
                TrackerDeviceSummary(
                    device = TrackerDevice(
                        deviceId = TrackerId("demo-tracker-alpha"),
                        displayName = "[DEMO] Tracker Alpha (Simulated)",
                        deviceModel = "Demo GPS Tracker v1",
                        manufacturer = "Trace Labs",
                        registrationTimestamp = 1700000000000L,
                        lastSeenTimestamp = now,
                        lastHeartbeatTimestamp = now,
                        batteryLevel = 92,
                        networkState = "WIFI",
                        connectionState = ConnectionState.CONNECTED,
                        health = DeviceHealth.HEALTHY
                    ),
                    latestLocation = Location(
                        deviceId = TrackerId("demo-tracker-alpha"),
                        latitude = 37.421998,
                        longitude = -122.084000,
                        accuracy = 4.5f,
                        bearing = 45.0f,
                        speed = 1.2f,
                        timestamp = now,
                        state = LocationState.LIVE
                    ),
                    locationState = LocationState.LIVE
                ),
                TrackerDeviceSummary(
                    device = TrackerDevice(
                        deviceId = TrackerId("demo-tracker-beta"),
                        displayName = "[DEMO] Tracker Beta (Simulated)",
                        deviceModel = "Demo Cellular Tracker v2",
                        manufacturer = "Trace Labs",
                        registrationTimestamp = 1700000000000L,
                        lastSeenTimestamp = now,
                        lastHeartbeatTimestamp = now,
                        batteryLevel = 78,
                        networkState = "CELLULAR",
                        connectionState = ConnectionState.CONNECTED,
                        health = DeviceHealth.HEALTHY
                    ),
                    latestLocation = Location(
                        deviceId = TrackerId("demo-tracker-beta"),
                        latitude = 37.424500,
                        longitude = -122.086000,
                        accuracy = 5.0f,
                        bearing = 135.0f,
                        speed = 0.8f,
                        timestamp = now,
                        state = LocationState.LIVE
                    ),
                    locationState = LocationState.LIVE
                )
            )
        }
    }

    operator fun invoke(): Flow<List<TrackerDeviceSummary>> {
        val baseFlow = combine(
            deviceRepository.observeAllDevices(),
            locationRepository.getAllLocations()
        ) { devices, locations ->
            val locationMap = locations.associateBy { it.deviceId }
            devices.map { device ->
                val locationRecord = locationMap[device.deviceId]
                val effectiveState = when {
                    device.connectionState == ConnectionState.CONNECTED && locationRecord != null -> {
                        LocationState.LIVE
                    }
                    else -> locationRecord?.state ?: LocationState.UNKNOWN
                }
                TrackerDeviceSummary(
                    device = device,
                    latestLocation = locationRecord,
                    locationState = effectiveState
                )
            }
        }

        if (sessionRepository == null) {
            return baseFlow.distinctUntilChanged()
        }

        return combine(
            baseFlow,
            sessionRepository.observeActiveSession()
        ) { summaries, session ->
            val user = session?.user
            when (user?.role) {
                Role.VISITOR -> {
                    DEMO_SUMMARIES
                }
                Role.USER -> {
                    val allowedDeviceIds = user.devicePermissions.map { it.deviceId }.toSet()
                    summaries.filter { it.device.deviceId.value in allowedDeviceIds }
                }
                Role.ADMIN, Role.OWNER -> {
                    summaries
                }
                null -> {
                    summaries
                }
            }
        }.distinctUntilChanged()
    }
}
