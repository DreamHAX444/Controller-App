package com.livetracker.controller.core.tracker.location.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface TrackerLocationRepository {
    fun getLocation(deviceId: TrackerId): Flow<Location?>
    fun getAllLocations(): Flow<List<Location>>
    suspend fun updateLocation(location: Location)
    suspend fun updateLocationState(deviceId: TrackerId, state: LocationState)
    suspend fun clearLocation(deviceId: TrackerId)
}
