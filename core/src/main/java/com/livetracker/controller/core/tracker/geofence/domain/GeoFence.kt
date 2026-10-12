package com.livetracker.controller.core.tracker.geofence.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint2D(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class GeoFence(
    val id: String,
    val name: String,
    val vertices: List<GeoPoint2D>,
    val colorArgb: Int,
    val isEnabled: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class GeoFenceState {
    INSIDE,
    OUTSIDE,
    UNKNOWN
}

interface GeoFenceRepository {
    suspend fun addGeoFence(geoFence: GeoFence)
    suspend fun removeGeoFence(id: String)
    suspend fun getGeoFences(): List<GeoFence>
}

interface GeoFenceStateRepository {
    suspend fun getState(deviceId: TrackerId, geoFenceId: String): GeoFenceState
    suspend fun updateState(deviceId: TrackerId, geoFenceId: String, state: GeoFenceState)
}
