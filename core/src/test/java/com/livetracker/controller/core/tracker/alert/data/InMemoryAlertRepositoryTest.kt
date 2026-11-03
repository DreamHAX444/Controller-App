package com.livetracker.controller.core.tracker.alert.data

import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertSeverity
import com.livetracker.controller.core.tracker.alert.domain.AlertType
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryAlertRepositoryTest {

    private lateinit var repository: InMemoryAlertRepository
    private val deviceA = TrackerId("tracker-a")
    private val deviceB = TrackerId("tracker-b")

    @Before
    fun setup() {
        repository = InMemoryAlertRepository()
    }

    @Test
    fun `addAlert emits immediately on observeUnacknowledgedAlerts`() = runTest {
        val alert = Alert(
            id = "alert-1",
            deviceId = deviceA,
            type = AlertType.GEOFENCE_ENTER,
            message = "Device entered Safe Zone",
            timestamp = 1000L,
            severity = AlertSeverity.INFO
        )

        repository.addAlert(alert)

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(1, unacknowledged.size)
        assertEquals("alert-1", unacknowledged[0].id)
        assertEquals(AlertSeverity.INFO, unacknowledged[0].severity)
    }

    @Test
    fun `alerts are sorted by descending timestamp`() = runTest {
        val older = Alert(
            id = "alert-1",
            deviceId = deviceA,
            type = AlertType.GEOFENCE_ENTER,
            message = "Older event",
            timestamp = 1000L
        )
        val newer = Alert(
            id = "alert-2",
            deviceId = deviceA,
            type = AlertType.GEOFENCE_EXIT,
            message = "Newer event",
            timestamp = 5000L
        )

        repository.addAlert(older)
        repository.addAlert(newer)

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(2, unacknowledged.size)
        assertEquals("alert-2", unacknowledged[0].id)
        assertEquals("alert-1", unacknowledged[1].id)
    }

    @Test
    fun `acknowledgeAlert marks alert and removes from unacknowledged stream`() = runTest {
        val alert1 = Alert("alert-1", deviceA, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", deviceB, AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        repository.addAlert(alert1)
        repository.addAlert(alert2)

        repository.acknowledgeAlert("alert-1")

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(1, unacknowledged.size)
        assertEquals("alert-2", unacknowledged[0].id)
    }

    @Test
    fun `acknowledgeAllAlerts clears all unacknowledged alerts`() = runTest {
        val alert1 = Alert("alert-1", deviceA, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", deviceB, AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        repository.addAlert(alert1)
        repository.addAlert(alert2)

        repository.acknowledgeAllAlerts()

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertTrue(unacknowledged.isEmpty())
    }

    @Test
    fun `acknowledgeAllAlerts for specific device only clears that device alerts`() = runTest {
        val alert1 = Alert("alert-1", deviceA, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", deviceB, AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        repository.addAlert(alert1)
        repository.addAlert(alert2)

        repository.acknowledgeAllAlerts(deviceA)

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(1, unacknowledged.size)
        assertEquals("alert-2", unacknowledged[0].id)
    }

    @Test
    fun `observeUnacknowledgedAlerts filtered by deviceId returns only device alerts`() = runTest {
        val alert1 = Alert("alert-1", deviceA, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", deviceB, AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        repository.addAlert(alert1)
        repository.addAlert(alert2)

        val deviceAAlerts = repository.observeUnacknowledgedAlerts(deviceA).first()
        assertEquals(1, deviceAAlerts.size)
        assertEquals("alert-1", deviceAAlerts[0].id)
    }

    @Test
    fun `acknowledgeAlerts acknowledges specific subset of alerts atomically`() = runTest {
        val alert1 = Alert("alert-1", deviceA, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", deviceB, AlertType.CONNECTION_LOST, "Msg 2", 2000L)
        val alert3 = Alert("alert-3", deviceA, AlertType.GEOFENCE_EXIT, "Msg 3", 3000L)

        repository.addAlert(alert1)
        repository.addAlert(alert2)
        repository.addAlert(alert3)

        repository.acknowledgeAlerts(setOf("alert-1", "alert-3"))

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(1, unacknowledged.size)
        assertEquals("alert-2", unacknowledged[0].id)
    }

    @Test
    fun `alerts with identical timestamps use severity and id as stable deterministic tie-breaker`() = runTest {
        val alertInfo = Alert("alert-a", deviceA, AlertType.GEOFENCE_ENTER, "Info", 1000L, severity = AlertSeverity.INFO)
        val alertCrit = Alert("alert-b", deviceA, AlertType.CONNECTION_LOST, "Crit", 1000L, severity = AlertSeverity.CRITICAL)
        val alertWarn = Alert("alert-c", deviceA, AlertType.GEOFENCE_EXIT, "Warn", 1000L, severity = AlertSeverity.WARNING)

        repository.addAlert(alertInfo)
        repository.addAlert(alertCrit)
        repository.addAlert(alertWarn)

        val unacknowledged = repository.observeUnacknowledgedAlerts().first()
        assertEquals(3, unacknowledged.size)
        // CRITICAL (ordinal 2) > WARNING (ordinal 1) > INFO (ordinal 0)
        assertEquals("alert-b", unacknowledged[0].id)
        assertEquals("alert-c", unacknowledged[1].id)
        assertEquals("alert-a", unacknowledged[2].id)
    }
}
