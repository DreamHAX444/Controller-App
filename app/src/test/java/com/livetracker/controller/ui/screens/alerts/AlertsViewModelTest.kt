package com.livetracker.controller.ui.screens.alerts

import androidx.lifecycle.SavedStateHandle
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.alert.domain.AlertSeverity
import com.livetracker.controller.core.tracker.alert.domain.AlertType
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class TestAlertRepository : AlertRepository {
        val alertsFlow = MutableStateFlow<List<Alert>>(emptyList())
        val acknowledgedAlertIds = mutableListOf<String>()
        var acknowledgedAllCount = 0

        override suspend fun addAlert(alert: Alert) {
            alertsFlow.value = alertsFlow.value + alert
        }

        override suspend fun acknowledgeAlert(alertId: String) {
            acknowledgedAlertIds.add(alertId)
            alertsFlow.value = alertsFlow.value.filter { it.id != alertId }
        }

        override suspend fun acknowledgeAlerts(alertIds: Set<String>) {
            acknowledgedAlertIds.addAll(alertIds)
            alertsFlow.value = alertsFlow.value.filter { !alertIds.contains(it.id) }
        }

        override suspend fun acknowledgeAllAlerts(deviceId: TrackerId?) {
            acknowledgedAllCount++
            alertsFlow.value = if (deviceId == null) emptyList() else alertsFlow.value.filter { it.deviceId != deviceId }
        }

        override suspend fun getUnacknowledgedAlerts(deviceId: TrackerId?): List<Alert> =
            if (deviceId == null) alertsFlow.value else alertsFlow.value.filter { it.deviceId == deviceId }

        override fun observeUnacknowledgedAlerts(deviceId: TrackerId?): Flow<List<Alert>> =
            if (deviceId == null) alertsFlow else alertsFlow.map { it.filter { alert -> alert.deviceId == deviceId } }
    }

    private class TestTrackerDeviceRepository : TrackerDeviceRepository {
        val devicesFlow = MutableStateFlow<List<TrackerDevice>>(emptyList())

        override suspend fun registerDevice(device: TrackerDevice): Result<Unit> {
            devicesFlow.value = devicesFlow.value + device
            return Result.success(Unit)
        }

        override suspend fun unregisterDevice(deviceId: TrackerId): Result<Unit> {
            devicesFlow.value = devicesFlow.value.filter { it.deviceId != deviceId }
            return Result.success(Unit)
        }

        override suspend fun updateDevice(device: TrackerDevice): Result<Unit> {
            devicesFlow.value = devicesFlow.value.map { if (it.deviceId == device.deviceId) device else it }
            return Result.success(Unit)
        }

        override suspend fun updateDevice(deviceId: TrackerId, modifier: (TrackerDevice) -> TrackerDevice): Result<Unit> {
            devicesFlow.value = devicesFlow.value.map { if (it.deviceId == deviceId) modifier(it) else it }
            return Result.success(Unit)
        }

        override suspend fun getDevice(deviceId: TrackerId): TrackerDevice? =
            devicesFlow.value.find { it.deviceId == deviceId }

        override suspend fun getAllDevices(): List<TrackerDevice> = devicesFlow.value

        override fun observeDevice(deviceId: TrackerId): Flow<TrackerDevice?> =
            devicesFlow.map { it.find { dev -> dev.deviceId == deviceId } }

        override fun observeAllDevices(): Flow<List<TrackerDevice>> = devicesFlow
    }

    private lateinit var alertRepository: TestAlertRepository
    private lateinit var deviceRepository: TestTrackerDeviceRepository
    private lateinit var viewModel: AlertsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        alertRepository = TestAlertRepository()
        deviceRepository = TestTrackerDeviceRepository()
        viewModel = AlertsViewModel(
            alertRepository = alertRepository,
            trackerDeviceRepository = deviceRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `alerts StateFlow emits unacknowledged alerts from repository`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.alerts.collect()
        }

        val alert = Alert(
            id = "alert-1",
            deviceId = TrackerId("tracker-1"),
            type = AlertType.GEOFENCE_ENTER,
            message = "Entered fence",
            timestamp = 1000L,
            severity = AlertSeverity.INFO
        )

        alertRepository.addAlert(alert)
        advanceUntilIdle()

        val activeAlerts = viewModel.alerts.value
        assertEquals(1, activeAlerts.size)
        assertEquals("alert-1", activeAlerts[0].id)
    }

    @Test
    fun `acknowledgeAlert invokes repository acknowledge method`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.alerts.collect()
        }

        val alert = Alert(
            id = "alert-1",
            deviceId = TrackerId("tracker-1"),
            type = AlertType.GEOFENCE_EXIT,
            message = "Exited fence",
            timestamp = 2000L,
            severity = AlertSeverity.WARNING
        )

        alertRepository.addAlert(alert)
        advanceUntilIdle()

        viewModel.acknowledgeAlert("alert-1")
        advanceUntilIdle()

        assertTrue(alertRepository.acknowledgedAlertIds.contains("alert-1"))
        assertTrue(viewModel.alerts.value.isEmpty())
    }

    @Test
    fun `acknowledgeAll invokes repository acknowledgeAllAlerts method`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.alerts.collect()
        }

        val alert1 = Alert("alert-1", TrackerId("t1"), AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("alert-2", TrackerId("t2"), AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        alertRepository.addAlert(alert1)
        alertRepository.addAlert(alert2)
        advanceUntilIdle()

        viewModel.acknowledgeAll()
        advanceUntilIdle()

        assertEquals(1, alertRepository.acknowledgedAllCount)
        assertTrue(viewModel.alerts.value.isEmpty())
    }

    @Test
    fun `severity filtering filters alerts while retaining accurate total counts`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        val alertCrit = Alert("a-1", TrackerId("t1"), AlertType.CONNECTION_LOST, "Crit msg", 1000L, severity = AlertSeverity.CRITICAL)
        val alertWarn = Alert("a-2", TrackerId("t1"), AlertType.LOW_BATTERY, "Warn msg", 2000L, severity = AlertSeverity.WARNING)
        val alertInfo = Alert("a-3", TrackerId("t2"), AlertType.GEOFENCE_ENTER, "Info msg", 3000L, severity = AlertSeverity.INFO)

        alertRepository.addAlert(alertCrit)
        alertRepository.addAlert(alertWarn)
        alertRepository.addAlert(alertInfo)
        advanceUntilIdle()

        // Initially ALL
        var state = viewModel.uiState.value
        assertEquals(3, state.totalUnacknowledgedCount)
        assertEquals(1, state.criticalCount)
        assertEquals(1, state.warningCount)
        assertEquals(1, state.infoCount)
        assertEquals(3, state.filteredAlerts.size)
        assertFalse(state.isFiltered)

        // Filter by CRITICAL
        viewModel.setSeverityFilter(SeverityFilter.CRITICAL)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.filteredAlerts.size)
        assertEquals("a-1", state.filteredAlerts[0].id)
        assertEquals(3, state.totalUnacknowledgedCount) // True total not changed!
        assertTrue(state.isFiltered)

        // Filter by WARNING
        viewModel.setSeverityFilter(SeverityFilter.WARNING)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.filteredAlerts.size)
        assertEquals("a-2", state.filteredAlerts[0].id)

        // Filter by INFO
        viewModel.setSeverityFilter(SeverityFilter.INFO)
        advanceUntilIdle()
        state = viewModel.uiState.value
        assertEquals(1, state.filteredAlerts.size)
        assertEquals("a-3", state.filteredAlerts[0].id)
    }

    @Test
    fun `tracker device filtering isolates alerts for specific tracker`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        val tracker1 = TrackerId("tracker-1")
        val tracker2 = TrackerId("tracker-2")

        val alert1 = Alert("a-1", tracker1, AlertType.GEOFENCE_ENTER, "T1 Alert", 1000L)
        val alert2 = Alert("a-2", tracker2, AlertType.CONNECTION_LOST, "T2 Alert", 2000L)

        alertRepository.addAlert(alert1)
        alertRepository.addAlert(alert2)
        advanceUntilIdle()

        viewModel.setDeviceFilter(tracker1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.filteredAlerts.size)
        assertEquals("a-1", state.filteredAlerts[0].id)
        assertEquals(2, state.totalUnacknowledgedCount)
        assertTrue(state.isFiltered)
    }

    @Test
    fun `combining severity and device filter matches intersection only`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        val tracker1 = TrackerId("tracker-1")
        val tracker2 = TrackerId("tracker-2")

        val a1 = Alert("a-1", tracker1, AlertType.CONNECTION_LOST, "T1 Crit", 1000L, severity = AlertSeverity.CRITICAL)
        val a2 = Alert("a-2", tracker1, AlertType.LOW_BATTERY, "T1 Warn", 2000L, severity = AlertSeverity.WARNING)
        val a3 = Alert("a-3", tracker2, AlertType.CONNECTION_LOST, "T2 Crit", 3000L, severity = AlertSeverity.CRITICAL)

        alertRepository.addAlert(a1)
        alertRepository.addAlert(a2)
        alertRepository.addAlert(a3)
        advanceUntilIdle()

        // Filter tracker 1 AND CRITICAL
        viewModel.setDeviceFilter(tracker1)
        viewModel.setSeverityFilter(SeverityFilter.CRITICAL)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.filteredAlerts.size)
        assertEquals("a-1", state.filteredAlerts[0].id)
    }

    @Test
    fun `friendly tracker names are resolved with fallback to deviceId value`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        val t1 = TrackerId("tracker-1")
        val t2 = TrackerId("tracker-2")

        val dev1 = TrackerDevice(
            deviceId = t1,
            displayName = "Rover Alpha",
            registrationTimestamp = 1000L
        )
        deviceRepository.registerDevice(dev1)

        val alert1 = Alert("a-1", t1, AlertType.GEOFENCE_ENTER, "Msg 1", 1000L)
        val alert2 = Alert("a-2", t2, AlertType.CONNECTION_LOST, "Msg 2", 2000L)

        alertRepository.addAlert(alert1)
        alertRepository.addAlert(alert2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Rover Alpha", state.deviceNames[t1])
        assertEquals("tracker-2", state.deviceNames[t2]) // Fallback to ID
    }

    @Test
    fun `scoped acknowledgeFiltered acknowledges only currently filtered alerts`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        val t1 = TrackerId("tracker-1")
        val t2 = TrackerId("tracker-2")

        val a1 = Alert("a-1", t1, AlertType.CONNECTION_LOST, "Crit 1", 1000L, severity = AlertSeverity.CRITICAL)
        val a2 = Alert("a-2", t2, AlertType.LOW_BATTERY, "Warn 2", 2000L, severity = AlertSeverity.WARNING)

        alertRepository.addAlert(a1)
        alertRepository.addAlert(a2)
        advanceUntilIdle()

        // Filter for CRITICAL only (matches only a-1)
        viewModel.setSeverityFilter(SeverityFilter.CRITICAL)
        advanceUntilIdle()

        viewModel.acknowledgeFiltered()
        advanceUntilIdle()

        // a-1 must be acknowledged, but a-2 must NOT be touched
        assertTrue(alertRepository.acknowledgedAlertIds.contains("a-1"))
        assertFalse(alertRepository.acknowledgedAlertIds.contains("a-2"))

        val unacknowledged = alertRepository.getUnacknowledgedAlerts()
        assertEquals(1, unacknowledged.size)
        assertEquals("a-2", unacknowledged[0].id)
    }

    @Test
    fun `SavedStateHandle restores severity and device filters across configuration change`() = runTest {
        val savedState = SavedStateHandle(
            mapOf(
                AlertsViewModel.KEY_SEVERITY_FILTER to SeverityFilter.WARNING.name,
                AlertsViewModel.KEY_DEVICE_FILTER to "tracker-restored"
            )
        )

        val restoredViewModel = AlertsViewModel(
            alertRepository = alertRepository,
            trackerDeviceRepository = deviceRepository,
            savedStateHandle = savedState
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            restoredViewModel.uiState.collect()
        }

        advanceUntilIdle()

        val state = restoredViewModel.uiState.value
        assertEquals(SeverityFilter.WARNING, state.selectedSeverity)
        assertEquals(TrackerId("tracker-restored"), state.selectedDeviceId)
        assertTrue(state.isFiltered)
    }

    @Test
    fun `clearFilters resets both severity and device filters`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.setSeverityFilter(SeverityFilter.CRITICAL)
        viewModel.setDeviceFilter(TrackerId("tracker-x"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFiltered)

        viewModel.clearFilters()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SeverityFilter.ALL, state.selectedSeverity)
        assertEquals(null, state.selectedDeviceId)
        assertFalse(state.isFiltered)
    }
}
