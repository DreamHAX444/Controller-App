package com.livetracker.controller.core.tracker.alert.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

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
    val isAcknowledged: Boolean = false,
    val geofenceId: String? = null,
    val geofenceName: String? = null,
    val soundProfile: String? = null,
    val durationSeconds: Int = 30,
    val isVibrationEnabled: Boolean = true,
    val severity: AlertSeverity = when (type) {
        AlertType.GEOFENCE_ENTER -> AlertSeverity.WARNING
        AlertType.GEOFENCE_EXIT -> AlertSeverity.CRITICAL
        AlertType.CONNECTION_LOST -> AlertSeverity.CRITICAL
        AlertType.LOW_BATTERY -> AlertSeverity.CRITICAL
    }
)

interface AlertRepository {
    suspend fun addAlert(alert: Alert)
    suspend fun acknowledgeAlert(alertId: String)
    suspend fun acknowledgeAlerts(alertIds: Set<String>) {
        alertIds.forEach { acknowledgeAlert(it) }
    }
    suspend fun acknowledgeAllAlerts(deviceId: TrackerId? = null)
    suspend fun getUnacknowledgedAlerts(deviceId: TrackerId? = null): List<Alert>
    fun observeUnacknowledgedAlerts(deviceId: TrackerId? = null): Flow<List<Alert>>
}

