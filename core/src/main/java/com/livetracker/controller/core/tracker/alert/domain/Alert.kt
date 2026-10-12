package com.livetracker.controller.core.tracker.alert.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class AlertType {
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    CONNECTION_LOST,
    LOW_BATTERY
}

data class Alert(
    val id: String,
    val deviceId: TrackerId,
    val type: AlertType,
    val message: String,
    val timestamp: Long,
    val isAcknowledged: Boolean = false
)

interface AlertRepository {
    suspend fun addAlert(alert: Alert)
    suspend fun acknowledgeAlert(alertId: String)
    suspend fun getUnacknowledgedAlerts(deviceId: TrackerId? = null): List<Alert>
}
