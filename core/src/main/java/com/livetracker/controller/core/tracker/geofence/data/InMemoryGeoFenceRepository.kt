package com.livetracker.controller.core.tracker.geofence.data

import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryGeoFenceRepository : GeoFenceRepository {
    private val mutex = Mutex()
    private val geoFences = mutableMapOf<String, GeoFence>()

    override suspend fun addGeoFence(geoFence: GeoFence) {
        mutex.withLock {
            geoFences[geoFence.id] = geoFence
        }
    }

    override suspend fun removeGeoFence(id: String) {
        mutex.withLock {
            geoFences.remove(id)
        }
    }

    override suspend fun getGeoFences(): List<GeoFence> {
        return mutex.withLock {
            geoFences.values.toList()
        }
    }
}
