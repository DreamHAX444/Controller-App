package com.livetracker.controller.core.tracker.alert.data

import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryAlertRepository : AlertRepository {
    private val mutex = Mutex()
    private val alerts = mutableMapOf<String, Alert>()
    private val _alertsFlow = MutableStateFlow<Map<String, Alert>>(emptyMap())

    override suspend fun addAlert(alert: Alert) {
        mutex.withLock {
            alerts[alert.id] = alert
            _alertsFlow.value = alerts.toMap()
        }
    }

    override suspend fun acknowledgeAlert(alertId: String) {
        mutex.withLock {
            val alert = alerts[alertId]
            if (alert != null) {
                alerts[alertId] = alert.copy(isAcknowledged = true)
                _alertsFlow.value = alerts.toMap()
            }
        }
    }

    override suspend fun acknowledgeAlerts(alertIds: Set<String>) {
        if (alertIds.isEmpty()) return
        mutex.withLock {
            var changed = false
            alertIds.forEach { id ->
                val alert = alerts[id]
                if (alert != null && !alert.isAcknowledged) {
                    alerts[id] = alert.copy(isAcknowledged = true)
                    changed = true
                }
            }
            if (changed) {
                _alertsFlow.value = alerts.toMap()
            }
        }
    }

    override suspend fun acknowledgeAllAlerts(deviceId: TrackerId?) {
        mutex.withLock {
            var changed = false
            alerts.forEach { (id, alert) ->
                if (!alert.isAcknowledged && (deviceId == null || alert.deviceId == deviceId)) {
                    alerts[id] = alert.copy(isAcknowledged = true)
                    changed = true
                }
            }
            if (changed) {
                _alertsFlow.value = alerts.toMap()
            }
        }
    }

    override suspend fun getUnacknowledgedAlerts(deviceId: TrackerId?): List<Alert> {
        return mutex.withLock {
            alerts.values.filter { 
                !it.isAcknowledged && (deviceId == null || it.deviceId == deviceId)
            }.sortedWith(ALERT_COMPARATOR).toList()
        }
    }

    override fun observeUnacknowledgedAlerts(deviceId: TrackerId?): Flow<List<Alert>> {
        return _alertsFlow.map { map ->
            map.values
                .filter { !it.isAcknowledged && (deviceId == null || it.deviceId == deviceId) }
                .sortedWith(ALERT_COMPARATOR)
        }
    }

    companion object {
        val ALERT_COMPARATOR = Comparator<Alert> { a, b ->
            val timeDiff = b.timestamp.compareTo(a.timestamp)
            if (timeDiff != 0) return@Comparator timeDiff
            val sevDiff = b.severity.ordinal.compareTo(a.severity.ordinal)
            if (sevDiff != 0) return@Comparator sevDiff
            b.id.compareTo(a.id)
        }
    }
}

