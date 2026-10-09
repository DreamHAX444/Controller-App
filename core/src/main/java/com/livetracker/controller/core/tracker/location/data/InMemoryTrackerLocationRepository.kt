package com.livetracker.controller.core.tracker.location.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryTrackerLocationRepository : TrackerLocationRepository {
    
    private val locationsState = MutableStateFlow<Map<TrackerId, Location>>(emptyMap())
    private val mutex = Mutex()

    override fun getLocation(deviceId: TrackerId): Flow<Location?> {
        return locationsState.map { it[deviceId] }
    }

    override fun getAllLocations(): Flow<List<Location>> {
        return locationsState.map { it.values.toList() }
    }

    override suspend fun updateLocation(location: Location) {
        mutex.withLock {
            locationsState.update { current ->
                val existing = current[location.deviceId]
                // Only update if newer
                if (existing == null || existing.timestamp <= location.timestamp) {
                    current + (location.deviceId to location)
                } else {
                    current
                }
            }
        }
    }

    override suspend fun updateLocationState(deviceId: TrackerId, state: LocationState) {
        mutex.withLock {
            locationsState.update { current ->
                val existing = current[deviceId]
                if (existing != null && existing.state != state) {
                    current + (deviceId to existing.copy(state = state))
                } else {
                    current
                }
            }
        }
    }

    override suspend fun clearLocation(deviceId: TrackerId) {
        mutex.withLock {
            locationsState.update { current ->
                current - deviceId
            }
        }
    }
}
