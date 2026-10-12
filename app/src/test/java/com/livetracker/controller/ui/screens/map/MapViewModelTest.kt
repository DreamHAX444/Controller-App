package com.livetracker.controller.ui.screens.map

import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceRepository
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.core.tracker.map.domain.ObserveMapStateUseCase
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.geofence.GeofenceEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeDeviceSelectionManager : DeviceSelectionManager {
        val flow = MutableStateFlow<DeviceSelection>(DeviceSelection.All)
        override fun getSelection() = flow
        override fun selectAll() { flow.value = DeviceSelection.All }
        override fun clearSelection() { flow.value = DeviceSelection.Empty }
        override fun selectDevice(deviceId: TrackerId) { flow.value = DeviceSelection.Single(deviceId) }
        override fun selectMultipleDevices(deviceIds: Set<TrackerId>) { flow.value = DeviceSelection.Multiple(deviceIds) }
    }

    private class FakeTrackerDeviceRepository : TrackerDeviceRepository {
        val devicesFlow = MutableStateFlow<List<TrackerDevice>>(emptyList())
        override suspend fun getAllDevices() = devicesFlow.value
        override fun observeAllDevices() = devicesFlow
        override fun observeDevice(deviceId: TrackerId) = throw NotImplementedError()
        override suspend fun getDevice(deviceId: TrackerId): TrackerDevice? = devicesFlow.value.find { it.deviceId == deviceId }
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
    }

    private class FakeAlertRepository : com.livetracker.controller.core.tracker.alert.domain.AlertRepository {
        val alerts = mutableListOf<com.livetracker.controller.core.tracker.alert.domain.Alert>()
        override suspend fun addAlert(alert: com.livetracker.controller.core.tracker.alert.domain.Alert) { alerts.add(alert) }
        override suspend fun acknowledgeAlert(alertId: String) {}
        override suspend fun getUnacknowledgedAlerts(deviceId: TrackerId?) = alerts.toList()
    }

    private lateinit var geoFenceRepository: InMemoryGeoFenceRepository
    private lateinit var geoFenceStateRepository: com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceStateRepository
    private lateinit var alertRepository: FakeAlertRepository
    private lateinit var evaluateGeoFencesUseCase: com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase
    private lateinit var deviceSelectionManager: FakeDeviceSelectionManager
    private lateinit var deviceRepository: FakeTrackerDeviceRepository
    private lateinit var locationRepository: InMemoryTrackerLocationRepository
    private lateinit var observeDeviceSummariesUseCase: ObserveDeviceSummariesUseCase
    private lateinit var observeMapStateUseCase: ObserveMapStateUseCase
    private lateinit var viewModel: MapViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        geoFenceRepository = InMemoryGeoFenceRepository()
        geoFenceStateRepository = com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceStateRepository()
        alertRepository = FakeAlertRepository()
        evaluateGeoFencesUseCase = com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase(
            geoFenceRepository,
            geoFenceStateRepository,
            alertRepository
        )
        deviceSelectionManager = FakeDeviceSelectionManager()
        deviceRepository = FakeTrackerDeviceRepository()
        locationRepository = InMemoryTrackerLocationRepository()

        observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepository, locationRepository)
        observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, deviceSelectionManager)

        viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = geoFenceRepository,
            mapPreferencesRepository = null,
            deviceSelectionManager = deviceSelectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepository,
            trackerLocationRepository = locationRepository,
            evaluateGeoFencesUseCase = evaluateGeoFencesUseCase,
            alertRepository = alertRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial states are correctly initialized`() {
        assertNull(viewModel.selectedMarker.value)
        assertTrue(viewModel.showGeoFences.value)
        assertEquals(GeofenceEditorState.Idle, viewModel.geofenceEditorState.value)
        assertEquals(MapProvider.OPEN_STREET_MAP, viewModel.mapConfiguration.value.provider)
        assertEquals(MapStyle.STANDARD, viewModel.mapConfiguration.value.style)
    }

    @Test
    fun `selectMarker updates selectedMarker state flow`() {
        val marker = MapMarker(
            deviceId = TrackerId("test-dev-1"),
            displayName = "Tracker 1",
            latitude = 40.7128,
            longitude = -74.0060,
            accuracy = 12f,
            bearing = 90f,
            speed = 15f,
            timestamp = 1000L,
            locationState = LocationState.LIVE,
            connectionState = ConnectionState.CONNECTED,
            healthState = DeviceHealth.HEALTHY,
            isSelected = true
        )

        viewModel.selectMarker(marker)
        assertEquals(marker, viewModel.selectedMarker.value)

        viewModel.selectMarker(null)
        assertNull(viewModel.selectedMarker.value)
    }

    @Test
    fun `toggleGeoFences toggles visibility`() {
        assertTrue(viewModel.showGeoFences.value)
        viewModel.toggleGeoFences()
        assertFalse(viewModel.showGeoFences.value)
        viewModel.toggleGeoFences()
        assertTrue(viewModel.showGeoFences.value)
    }

    @Test
    fun `geofence editor state transitions and creation workflow`() = runTest {
        // 1. Open list
        viewModel.openGeofenceList()
        assertEquals(GeofenceEditorState.ListMode, viewModel.geofenceEditorState.value)

        // 2. Start creation
        viewModel.startCreatingGeofence()
        val creatingState = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertNull(creatingState.id)
        assertEquals("", creatingState.name)
        assertTrue(creatingState.vertices.isEmpty())

        // 3. Add vertices
        viewModel.addVertex(40.7128, -74.0060)
        viewModel.addVertex(40.7150, -74.0040)
        val twoVertices = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals(2, twoVertices.vertices.size)
        assertFalse(twoVertices.hasMinVertices)

        // 4. Save validation: vertices < 3
        viewModel.updateEditingName("Downtown Zone")
        viewModel.saveGeofence()
        val validationErr1 = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertNotNull(validationErr1.validationError)

        // 5. Add third vertex to close polygon
        viewModel.addVertex(40.7100, -74.0030)
        val threeVertices = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals(3, threeVertices.vertices.size)
        assertTrue(threeVertices.hasMinVertices)

        // 6. Save successfully
        viewModel.saveGeofence()
        testScheduler.advanceUntilIdle()

        // Returned to list mode
        assertEquals(GeofenceEditorState.ListMode, viewModel.geofenceEditorState.value)
        val savedFences = geoFenceRepository.getGeoFences()
        assertEquals(1, savedFences.size)
        assertEquals("Downtown Zone", savedFences.first().name)
        assertEquals(3, savedFences.first().vertices.size)
    }

    @Test
    fun `geofence editing and undo vertex works correctly`() {
        viewModel.startCreatingGeofence()
        viewModel.addVertex(10.0, 20.0)
        viewModel.addVertex(30.0, 40.0)

        viewModel.undoLastVertex()
        val editing = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals(1, editing.vertices.size)
        assertEquals(10.0, editing.vertices.first().latitude, 0.001)

        viewModel.clearVertices()
        val cleared = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertTrue(cleared.vertices.isEmpty())
    }

    @Test
    fun `point in polygon ray casting detects fence on long click`() = runTest {
        val squareFence = GeoFence(
            id = "square_1",
            name = "Test Square",
            vertices = listOf(
                GeoPoint2D(0.0, 0.0),
                GeoPoint2D(0.0, 10.0),
                GeoPoint2D(10.0, 10.0),
                GeoPoint2D(10.0, 0.0)
            ),
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(squareFence)
        testScheduler.advanceUntilIdle()

        // Point outside
        viewModel.onMapLongClick(20.0, 20.0)
        testScheduler.advanceUntilIdle()
        assertEquals(GeofenceEditorState.Idle, viewModel.geofenceEditorState.value)

        // Point inside (5.0, 5.0)
        viewModel.onMapLongClick(5.0, 5.0)
        testScheduler.advanceUntilIdle()

        val editing = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals("square_1", editing.id)
        assertEquals("Test Square", editing.name)
    }

    @Test
    fun `toggleGeofenceEnabled updates repository`() = runTest {
        val fence = GeoFence(
            id = "f_1",
            name = "Zone 1",
            vertices = listOf(GeoPoint2D(0.0, 0.0), GeoPoint2D(0.0, 1.0), GeoPoint2D(1.0, 0.0)),
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(fence)
        testScheduler.advanceUntilIdle()

        viewModel.toggleGeofenceEnabled("f_1", false)
        testScheduler.advanceUntilIdle()

        val updated = geoFenceRepository.getGeoFences().first()
        assertFalse(updated.isEnabled)
    }

    @Test
    fun `deleteGeofence removes fence from repository`() = runTest {
        val fence = GeoFence(
            id = "f_delete",
            name = "Zone to delete",
            vertices = listOf(GeoPoint2D(0.0, 0.0), GeoPoint2D(0.0, 1.0), GeoPoint2D(1.0, 0.0)),
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(fence)
        testScheduler.advanceUntilIdle()

        viewModel.deleteGeofence("f_delete")
        testScheduler.advanceUntilIdle()

        assertTrue(geoFenceRepository.getGeoFences().isEmpty())
    }

    @Test
    fun `geoFences updates reactively when geofence is saved or deleted`() = runTest {
        backgroundScope.launch {
            viewModel.geoFences.collect {}
        }
        testScheduler.advanceUntilIdle()

        viewModel.startCreatingGeofence()
        viewModel.updateEditingName("Reactive Zone")
        viewModel.addVertex(0.0, 0.0)
        viewModel.addVertex(0.0, 1.0)
        viewModel.addVertex(1.0, 0.0)
        viewModel.saveGeofence()
        testScheduler.advanceUntilIdle()

        val fences = viewModel.geoFences.value
        assertEquals(1, fences.size)
        assertEquals("Reactive Zone", fences.first().name)
    }

    @Test
    fun `debugAddDevice registers device and location in repositories`() = runTest {
        viewModel.debugAddDevice(
            id = "test_dev_99",
            displayName = "Debug Drone",
            model = "Quadcopter X",
            lat = 40.7128,
            lon = -74.0060,
            battery = 95,
            speed = 10f,
            bearing = 45f,
            connectionState = ConnectionState.CONNECTED
        )
        testScheduler.advanceUntilIdle()

        val allDevices = deviceRepository.getAllDevices()
        assertEquals(1, allDevices.size)
        assertEquals("test_dev_99", allDevices.first().deviceId.value)
        assertEquals("Debug Drone", allDevices.first().displayName)

        val loc = locationRepository.getLocation(TrackerId("test_dev_99")).first()
        assertNotNull(loc)
        assertEquals(40.7128, loc!!.latitude, 0.0001)
        assertEquals(-74.0060, loc.longitude, 0.0001)
        assertEquals(95, allDevices.first().batteryLevel)
    }

    @Test
    fun `debugMoveDevice updates coordinates and evaluates geofence triggers`() = runTest {
        // Setup geofence around (10.0, 10.0)
        val fence = GeoFence(
            id = "zone_10",
            name = "Test Zone 10",
            vertices = listOf(
                GeoPoint2D(9.9, 9.9),
                GeoPoint2D(9.9, 10.1),
                GeoPoint2D(10.1, 10.1),
                GeoPoint2D(10.1, 9.9)
            ),
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(fence)
        testScheduler.advanceUntilIdle()

        // 1. Add device initially OUTSIDE zone
        viewModel.debugAddDevice(
            id = "mover_1",
            displayName = "Mover",
            model = "Van",
            lat = 5.0,
            lon = 5.0
        )
        testScheduler.advanceUntilIdle()
        // No alerts yet because previous state was UNKNOWN
        assertTrue(alertRepository.alerts.isEmpty())

        // 2. Teleport INSIDE the geofence
        viewModel.debugTeleportInsideGeofence(TrackerId("mover_1"), fence)
        testScheduler.advanceUntilIdle()

        // State transition OUTSIDE -> INSIDE triggers GEOFENCE_ENTER alert!
        assertEquals(1, alertRepository.alerts.size)
        assertEquals(com.livetracker.controller.core.tracker.alert.domain.AlertType.GEOFENCE_ENTER, alertRepository.alerts.first().type)
        assertTrue(alertRepository.alerts.first().message.contains("entered"))

        // 3. Teleport OUTSIDE the geofence
        viewModel.debugTeleportOutsideGeofence(TrackerId("mover_1"), fence)
        testScheduler.advanceUntilIdle()

        // State transition INSIDE -> OUTSIDE triggers GEOFENCE_EXIT alert!
        assertEquals(2, alertRepository.alerts.size)
        assertEquals(com.livetracker.controller.core.tracker.alert.domain.AlertType.GEOFENCE_EXIT, alertRepository.alerts.last().type)
        assertTrue(alertRepository.alerts.last().message.contains("exited"))
    }

    @Test
    fun `debugStepDevice offsets coordinates correctly`() = runTest {
        viewModel.debugAddDevice(
            id = "stepper_1",
            displayName = "Stepper",
            model = "Rover",
            lat = 30.0,
            lon = 30.0
        )
        testScheduler.advanceUntilIdle()

        // Step North by +0.005
        viewModel.debugStepDevice(TrackerId("stepper_1"), deltaLat = 0.005, deltaLon = 0.0)
        testScheduler.advanceUntilIdle()

        val loc = locationRepository.getLocation(TrackerId("stepper_1")).first()
        assertNotNull(loc)
        assertEquals(30.005, loc!!.latitude, 0.0001)
        assertEquals(30.0, loc.longitude, 0.0001)
    }

    @Test
    fun `debugRemoveDevice and clearAllDevices cleans up state`() = runTest {
        viewModel.debugSpawnPresetFleet()
        testScheduler.advanceUntilIdle()

        assertEquals(3, deviceRepository.getAllDevices().size)

        // Remove one device
        viewModel.debugRemoveDevice(TrackerId("alpha_van"))
        testScheduler.advanceUntilIdle()
        assertEquals(2, deviceRepository.getAllDevices().size)

        // Clear all remaining
        viewModel.debugClearAllDevices()
        testScheduler.advanceUntilIdle()
        assertEquals(0, deviceRepository.getAllDevices().size)
    }
}
