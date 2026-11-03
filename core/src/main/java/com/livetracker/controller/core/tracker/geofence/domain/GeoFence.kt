package com.livetracker.controller.core.tracker.geofence.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint2D(
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0
}

@Serializable
enum class GeoFenceType {
    POLYGON,
    CIRCLE
}

@Serializable
enum class GeofenceTriggerRule {
    ENTRY,
    EXIT,
    BOTH
}

@Serializable
enum class AlertSoundProfile(val displayName: String) {
    TACTICAL_SIREN("Tactical Siren"),
    RAPID_KLAXON("Rapid Klaxon"),
    PULSING_ALARM("Pulsing Alarm"),
    MILITARY_HORN("Military Horn"),
    DISCREET_CHIME("Discreet Chime")
}

@Serializable
data class GeoFence(
    val id: String,
    val name: String,
    val vertices: List<GeoPoint2D> = emptyList(),
    val colorArgb: Int,
    val isEnabled: Boolean,
    val type: GeoFenceType = GeoFenceType.POLYGON,
    val center: GeoPoint2D? = null,
    val radiusMeters: Double? = null,
    val triggerRule: GeofenceTriggerRule = GeofenceTriggerRule.BOTH,
    val soundProfile: AlertSoundProfile = AlertSoundProfile.TACTICAL_SIREN,
    val durationSeconds: Int = 30,
    val targetDeviceId: TrackerId? = null,
    val isVibrationEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isCircle: Boolean get() = type == GeoFenceType.CIRCLE
    val isPolygon: Boolean get() = type == GeoFenceType.POLYGON

    fun isValid(): Boolean {
        if (name.isBlank()) return false
        return when (type) {
            GeoFenceType.POLYGON -> {
                vertices.size >= 3 && vertices.all { it.isValid() }
            }
            GeoFenceType.CIRCLE -> {
                center != null && center.isValid() &&
                        radiusMeters != null && radiusMeters.isFinite() && radiusMeters > 0.0
            }
        }
    }

    companion object {
        fun createPolygon(
            id: String,
            name: String,
            vertices: List<GeoPoint2D>,
            colorArgb: Int,
            isEnabled: Boolean = true,
            triggerRule: GeofenceTriggerRule = GeofenceTriggerRule.BOTH,
            soundProfile: AlertSoundProfile = AlertSoundProfile.TACTICAL_SIREN,
            durationSeconds: Int = 30,
            targetDeviceId: TrackerId? = null,
            isVibrationEnabled: Boolean = true,
            createdAt: Long = System.currentTimeMillis(),
            updatedAt: Long = System.currentTimeMillis()
        ): GeoFence {
            require(name.isNotBlank()) { "Polygon geofence name cannot be blank" }
            require(vertices.size >= 3) { "Polygon geofence requires at least 3 vertices" }
            require(vertices.all { it.isValid() }) { "All polygon vertices must be valid geographic coordinates" }
            return GeoFence(
                id = id,
                name = name.trim(),
                vertices = vertices,
                colorArgb = colorArgb,
                isEnabled = isEnabled,
                type = GeoFenceType.POLYGON,
                triggerRule = triggerRule,
                soundProfile = soundProfile,
                durationSeconds = durationSeconds,
                targetDeviceId = targetDeviceId,
                isVibrationEnabled = isVibrationEnabled,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }

        fun createCircle(
            id: String,
            name: String,
            center: GeoPoint2D,
            radiusMeters: Double,
            colorArgb: Int,
            isEnabled: Boolean = true,
            triggerRule: GeofenceTriggerRule = GeofenceTriggerRule.BOTH,
            soundProfile: AlertSoundProfile = AlertSoundProfile.TACTICAL_SIREN,
            durationSeconds: Int = 30,
            targetDeviceId: TrackerId? = null,
            isVibrationEnabled: Boolean = true,
            createdAt: Long = System.currentTimeMillis(),
            updatedAt: Long = System.currentTimeMillis()
        ): GeoFence {
            require(name.isNotBlank()) { "Circle geofence name cannot be blank" }
            require(center.isValid()) { "Circle center must be valid geographic coordinates" }
            require(radiusMeters.isFinite() && radiusMeters > 0.0) { "Circle radius must be finite and strictly positive" }
            return GeoFence(
                id = id,
                name = name.trim(),
                vertices = emptyList(),
                colorArgb = colorArgb,
                isEnabled = isEnabled,
                type = GeoFenceType.CIRCLE,
                center = center,
                radiusMeters = radiusMeters,
                triggerRule = triggerRule,
                soundProfile = soundProfile,
                durationSeconds = durationSeconds,
                targetDeviceId = targetDeviceId,
                isVibrationEnabled = isVibrationEnabled,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }
}

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

