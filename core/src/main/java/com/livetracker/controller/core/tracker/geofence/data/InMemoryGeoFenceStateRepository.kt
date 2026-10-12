package com.livetracker.controller.core.tracker.geofence.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceState
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceStateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryGeoFenceStateRepository : GeoFenceStateRepository {
    private val mutex = Mutex()
    // key: "deviceId_geoFenceId"
    private val states = mutableMapOf<String, GeoFenceState>()

    override suspend fun getState(deviceId: TrackerId, geoFenceId: String): GeoFenceState {
        return mutex.withLock {
            states["${deviceId.value}_$geoFenceId"] ?: GeoFenceState.UNKNOWN
        }
    }

    override suspend fun updateState(deviceId: TrackerId, geoFenceId: String, state: GeoFenceState) {
        mutex.withLock {
            states["${deviceId.value}_$geoFenceId"] = state
        }
    }
}
