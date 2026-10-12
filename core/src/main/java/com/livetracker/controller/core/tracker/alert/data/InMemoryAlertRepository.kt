package com.livetracker.controller.core.tracker.alert.data

import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryAlertRepository : AlertRepository {
    private val mutex = Mutex()
    private val alerts = mutableMapOf<String, Alert>()

    override suspend fun addAlert(alert: Alert) {
        mutex.withLock {
            alerts[alert.id] = alert
        }
    }

    override suspend fun acknowledgeAlert(alertId: String) {
        mutex.withLock {
            val alert = alerts[alertId]
            if (alert != null) {
                alerts[alertId] = alert.copy(isAcknowledged = true)
            }
        }
    }

    override suspend fun getUnacknowledgedAlerts(deviceId: TrackerId?): List<Alert> {
        return mutex.withLock {
            alerts.values.filter { 
                !it.isAcknowledged && (deviceId == null || it.deviceId == deviceId)
            }.sortedByDescending { it.timestamp }.toList()
        }
    }
}
