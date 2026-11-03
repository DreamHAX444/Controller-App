package com.livetracker.controller.core.tracker.geofence.domain

import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.alert.domain.AlertType
import com.livetracker.controller.core.tracker.location.domain.Location
import java.util.UUID

class EvaluateGeoFencesUseCase(
    private val geoFenceRepository: GeoFenceRepository,
    private val geoFenceStateRepository: GeoFenceStateRepository,
    private val alertRepository: AlertRepository
) {
    suspend operator fun invoke(location: Location) {
        val deviceId = location.deviceId
        val geoFences = geoFenceRepository.getGeoFences().filter { it.isEnabled } // Only enabled geofences
        if (geoFences.isEmpty()) return

        for (geoFence in geoFences) {
            // Device target filtering: if targetDeviceId is set, only evaluate for that device
            if (geoFence.targetDeviceId != null && geoFence.targetDeviceId != deviceId) {
                continue
            }

            val isInside = when (geoFence.type) {
                GeoFenceType.CIRCLE -> {
                    val center = geoFence.center
                    val radius = geoFence.radiusMeters
                    if (center != null && radius != null && radius > 0.0) {
                        val distance = DistanceCalculator.calculateDistanceMeters(
                            lat1 = location.latitude,
                            lon1 = location.longitude,
                            lat2 = center.latitude,
                            lon2 = center.longitude
                        )
                        distance <= radius
                    } else {
                        false
                    }
                }
                GeoFenceType.POLYGON -> {
                    isPointInPolygon(location.latitude, location.longitude, geoFence.vertices)
                }
            }
            
            val currentState = if (isInside) GeoFenceState.INSIDE else GeoFenceState.OUTSIDE
            val previousState = geoFenceStateRepository.getState(deviceId, geoFence.id)

            val shouldTrigger = when {
                previousState == GeoFenceState.UNKNOWN -> {
                    currentState == GeoFenceState.INSIDE &&
                            (geoFence.triggerRule == GeofenceTriggerRule.ENTRY || geoFence.triggerRule == GeofenceTriggerRule.BOTH)
                }
                currentState == GeoFenceState.INSIDE && previousState == GeoFenceState.OUTSIDE -> {
                    geoFence.triggerRule == GeofenceTriggerRule.ENTRY || geoFence.triggerRule == GeofenceTriggerRule.BOTH
                }
                currentState == GeoFenceState.OUTSIDE && previousState == GeoFenceState.INSIDE -> {
                    geoFence.triggerRule == GeofenceTriggerRule.EXIT || geoFence.triggerRule == GeofenceTriggerRule.BOTH
                }
                else -> false
            }

            if (shouldTrigger) {
                val alertType = if (currentState == GeoFenceState.INSIDE) AlertType.GEOFENCE_ENTER else AlertType.GEOFENCE_EXIT
                val action = if (currentState == GeoFenceState.INSIDE) "entered" else "exited"
                val message = "Device has $action geo-fence: ${geoFence.name}"
                
                alertRepository.addAlert(
                    Alert(
                        id = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        type = alertType,
                        message = message,
                        timestamp = location.timestamp,
                        geofenceId = geoFence.id,
                        geofenceName = geoFence.name,
                        soundProfile = geoFence.soundProfile.name,
                        durationSeconds = geoFence.durationSeconds,
                        isVibrationEnabled = geoFence.isVibrationEnabled
                    )
                )
            }

            if (currentState != previousState) {
                geoFenceStateRepository.updateState(deviceId, geoFence.id, currentState)
            }
        }
    }

    // Ray-casting algorithm for Point-in-Polygon
    private fun isPointInPolygon(lat: Double, lon: Double, polygon: List<GeoPoint2D>): Boolean {
        if (polygon.size < 3) return false
        
        var isInside = false
        var j = polygon.size - 1
        
        for (i in polygon.indices) {
            val vi = polygon[i]
            val vj = polygon[j]
            
            val intersect = ((vi.longitude > lon) != (vj.longitude > lon)) &&
                    (lat < (vj.latitude - vi.latitude) * (lon - vi.longitude) / (vj.longitude - vi.longitude) + vi.latitude)
            if (intersect) {
                isInside = !isInside
            }
            j = i
        }
        return isInside
    }
}
