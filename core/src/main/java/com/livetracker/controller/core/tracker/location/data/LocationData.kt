package com.livetracker.controller.core.tracker.location.data

import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Wire-format location data matching the tracker application's Supabase telemetry payload.
 */
@Serializable
data class LocationData(
    @SerialName("device_id")
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speed: Float? = null,
    val accuracy: Float? = null,
    val bearing: Float? = null,
    val pitch: Float? = null,
    val roll: Float? = null,
    val pressure: Float? = null,
    @SerialName("battery_level")
    val batteryLevel: Float? = null,
    val charging: Boolean? = null,
    @SerialName("signal_dbm")
    val signalDbm: Int? = null,
    @SerialName("network_type")
    val networkType: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    val id: Long? = null
) {
    fun isValidCoordinates(): Boolean {
        return latitude.isFinite() &&
                longitude.isFinite() &&
                (accuracy == null || (accuracy.isFinite() && accuracy > 0f)) &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0
    }

    fun parseTimestamp(): Long {
        if (createdAt.isNullOrBlank()) return System.currentTimeMillis()
        val trimmed = createdAt.trim().trim('"')
        return try {
            Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(trimmed)
                    .atZone(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                trimmed.toLongOrNull() ?: System.currentTimeMillis()
            }
        }
    }

    fun toLocationUpdatedEvent(
        eventId: String = "loc-${UUID.randomUUID()}",
        parsedTimestamp: Long = parseTimestamp()
    ): TrackerEvent.LocationUpdated {
        return TrackerEvent.LocationUpdated(
            eventId = eventId,
            deviceId = TrackerId(deviceId),
            timestamp = parsedTimestamp,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy ?: 5f,
            bearing = bearing ?: 0f,
            speed = speed
        )
    }
}
