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
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
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
        val alertsFlow = MutableStateFlow<List<com.livetracker.controller.core.tracker.alert.domain.Alert>>(emptyList())
        val alerts: List<com.livetracker.controller.core.tracker.alert.domain.Alert> get() = alertsFlow.value

        override suspend fun addAlert(alert: com.livetracker.controller.core.tracker.alert.domain.Alert) {
            alertsFlow.value = alertsFlow.value + alert
        }
        override suspend fun acknowledgeAlert(alertId: String) {
            alertsFlow.value = alertsFlow.value.filter { it.id != alertId }
        }
        override suspend fun acknowledgeAllAlerts(deviceId: TrackerId?) {
            alertsFlow.value = if (deviceId != null) {
                alertsFlow.value.filter { it.deviceId != deviceId }
            } else {
                emptyList()
            }
        }
        override suspend fun getUnacknowledgedAlerts(deviceId: TrackerId?) =
            if (deviceId != null) alertsFlow.value.filter { it.deviceId == deviceId } else alertsFlow.value
        override fun observeUnacknowledgedAlerts(deviceId: TrackerId?) =
            if (deviceId != null) {
                kotlinx.coroutines.flow.flow {
                    alertsFlow.collect { list ->
                        emit(list.filter { it.deviceId == deviceId })
                    }
                }
            } else {
                alertsFlow
            }
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
    fun `startCreatingGeofence with CIRCLE initializes state and supports setting center and radius`() {
        viewModel.startCreatingGeofence(GeoFenceType.CIRCLE)
        val state = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing

        assertEquals(GeoFenceType.CIRCLE, state.shapeType)
        assertNull(state.center)
        assertEquals(200.0, state.radiusMeters, 0.001)

        // Set center
        viewModel.setCircleCenter(37.7749, -122.4194)
        val withCenter = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertNotNull(withCenter.center)
        assertEquals(37.7749, withCenter.center!!.latitude, 0.0001)
        assertEquals(-122.4194, withCenter.center!!.longitude, 0.0001)

        // Set and clamp radius
        viewModel.setCircleRadius(500.0)
        assertEquals(500.0, (viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).radiusMeters, 0.001)

        // Clamping to minimum 10m
        viewModel.setCircleRadius(2.0)
        assertEquals(10.0, (viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).radiusMeters, 0.001)

        // Clamping to maximum 50000m
        viewModel.setCircleRadius(999999.0)
        assertEquals(50000.0, (viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).radiusMeters, 0.001)
    }

    @Test
    fun `saveGeofence validates and saves circular geofence`() = runTest {
        viewModel.startCreatingGeofence(GeoFenceType.CIRCLE)
        viewModel.updateEditingName("HQ Perimeter")

        // Saving without center shows validation error
        viewModel.saveGeofence()
        val errorState = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals("Please tap on the map to set the circle center", errorState.validationError)

        // Add center and save successfully
        viewModel.setCircleCenter(37.7749, -122.4194)
        viewModel.setCircleRadius(350.0)
        viewModel.saveGeofence()
        testScheduler.advanceUntilIdle()

        assertEquals(GeofenceEditorState.ListMode, viewModel.geofenceEditorState.value)
        val savedFences = geoFenceRepository.getGeoFences()
        assertEquals(1, savedFences.size)
        val saved = savedFences.first()
        assertEquals("HQ Perimeter", saved.name)
        assertEquals(GeoFenceType.CIRCLE, saved.type)
        assertTrue(saved.isCircle)
        assertFalse(saved.isPolygon)
        assertNotNull(saved.center)
        assertEquals(37.7749, saved.center!!.latitude, 0.0001)
        assertEquals(350.0, saved.radiusMeters ?: 0.0, 0.001)
    }

    @Test
    fun `onMapLongClick hits circular geofence within radius`() = runTest {
        val circleFence = GeoFence.createCircle(
            id = "circle_hq",
            name = "Circle HQ",
            center = GeoPoint2D(37.7749, -122.4194),
            radiusMeters = 500.0,
            colorArgb = 0xFF2563EB.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(circleFence)
        testScheduler.advanceUntilIdle()

        // Point outside radius (approx 10km away)
        viewModel.onMapLongClick(37.85, -122.4194)
        testScheduler.advanceUntilIdle()
        assertEquals(GeofenceEditorState.Idle, viewModel.geofenceEditorState.value)

        // Point inside radius (at center)
        viewModel.onMapLongClick(37.7749, -122.4194)
        testScheduler.advanceUntilIdle()

        val editing = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals("circle_hq", editing.id)
        assertEquals("Circle HQ", editing.name)
        assertEquals(GeoFenceType.CIRCLE, editing.shapeType)
        assertEquals(500.0, editing.radiusMeters, 0.001)
    }

    @Test
    fun `circle geofence triggers transition alerts when device moves`() = runTest {
        val circleFence = GeoFence.createCircle(
            id = "alert_circle",
            name = "Alert Safe Zone",
            center = GeoPoint2D(0.0, 0.0),
            radiusMeters = 1000.0, // 1km radius
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(circleFence)

        val trackerId = TrackerId("circ_tracker")
        val now = System.currentTimeMillis()
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = trackerId,
                displayName = "Circle Runner",
                deviceModel = "GPS-01",
                registrationTimestamp = now,
                lastSeenTimestamp = now,
                connectionState = ConnectionState.CONNECTED,
                batteryLevel = 90
            )
        )

        // 1. Setup device outside the circle (10km away at 0.1 deg lat ~ 11.1km)
        val initialLoc = Location(
            deviceId = trackerId,
            latitude = 0.1,
            longitude = 0.0,
            accuracy = 8f,
            bearing = 0f,
            speed = 0f,
            timestamp = now,
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(initialLoc)
        evaluateGeoFencesUseCase.invoke(initialLoc)
        testScheduler.advanceUntilIdle()
        assertTrue(alertRepository.alerts.isEmpty())

        // 2. Move device inside the circle (at center 0.0, 0.0)
        val insideLoc = Location(
            deviceId = trackerId,
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 8f,
            bearing = 0f,
            speed = 0f,
            timestamp = System.currentTimeMillis(),
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(insideLoc)
        evaluateGeoFencesUseCase.invoke(insideLoc)
        testScheduler.advanceUntilIdle()

        // Transition OUTSIDE -> INSIDE triggers GEOFENCE_ENTER alert
        assertEquals(1, alertRepository.alerts.size)
        assertEquals(com.livetracker.controller.core.tracker.alert.domain.AlertType.GEOFENCE_ENTER, alertRepository.alerts.first().type)
        assertTrue(alertRepository.alerts.first().message.contains("entered"))

        // 3. Move device outside the circle
        val outsideLoc = Location(
            deviceId = trackerId,
            latitude = 0.1,
            longitude = 0.0,
            accuracy = 8f,
            bearing = 0f,
            speed = 0f,
            timestamp = System.currentTimeMillis(),
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(outsideLoc)
        evaluateGeoFencesUseCase.invoke(outsideLoc)
        testScheduler.advanceUntilIdle()

        // Transition INSIDE -> OUTSIDE triggers GEOFENCE_EXIT alert
        assertEquals(2, alertRepository.alerts.size)
        assertEquals(com.livetracker.controller.core.tracker.alert.domain.AlertType.GEOFENCE_EXIT, alertRepository.alerts.last().type)
        assertTrue(alertRepository.alerts.last().message.contains("exited"))
    }

    @Test
    fun `isDirty correctly evaluates new geofence draft state`() {
        // Initial empty draft is clean
        viewModel.startCreatingGeofence(GeoFenceType.POLYGON)
        val cleanState = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertFalse(cleanState.isDirty)

        // Typing name marks dirty
        viewModel.updateEditingName("Warehouse Zone")
        val withName = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertTrue(withName.isDirty)

        // Resetting name back to blank marks clean again
        viewModel.updateEditingName("")
        val resetName = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertFalse(resetName.isDirty)

        // Adding vertex marks dirty
        viewModel.addVertex(37.7749, -122.4194)
        val withVertex = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertTrue(withVertex.isDirty)

        // Undoing vertex returns to clean
        viewModel.undoLastVertex()
        val afterUndo = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertFalse(afterUndo.isDirty)

        // Setting circle center marks dirty
        viewModel.startCreatingGeofence(GeoFenceType.CIRCLE)
        val circleClean = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertFalse(circleClean.isDirty)

        viewModel.setCircleCenter(37.7749, -122.4194)
        val circleWithCenter = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertTrue(circleWithCenter.isDirty)
    }

    @Test
    fun `isDirty correctly evaluates edits on existing geofence`() {
        val originalPolygon = GeoFence.createPolygon(
            id = "f_orig",
            name = "Depot",
            vertices = listOf(GeoPoint2D(10.0, 10.0), GeoPoint2D(10.0, 11.0), GeoPoint2D(11.0, 10.0)),
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        viewModel.startEditingGeofence(originalPolygon)
        val baseline = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertFalse(baseline.isDirty)

        // Modifying name makes it dirty
        viewModel.updateEditingName("Depot North")
        assertTrue((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)
        viewModel.updateEditingName("Depot")
        assertFalse((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)

        // Modifying color makes it dirty
        viewModel.updateEditingColor(0xFF2563EB.toInt())
        assertTrue((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)
        viewModel.updateEditingColor(0xFF10B981.toInt())
        assertFalse((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)

        // Modifying enabled makes it dirty
        viewModel.updateEditingEnabled(false)
        assertTrue((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)
        viewModel.updateEditingEnabled(true)
        assertFalse((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)

        // Adding vertex makes it dirty
        viewModel.addVertex(12.0, 12.0)
        assertTrue((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)
        viewModel.undoLastVertex()
        assertFalse((viewModel.geofenceEditorState.value as GeofenceEditorState.Editing).isDirty)
    }

    @Test
    fun `addVertex suppresses accidental duplicate points within 8 meters`() {
        viewModel.startCreatingGeofence(GeoFenceType.POLYGON)
        // Add first vertex at (37.7749, -122.4194)
        viewModel.addVertex(37.7749, -122.4194)
        val state1 = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals(1, state1.vertices.size)

        // Tap near coordinate (~5.5 meters away: lat offset +0.00005 deg is ~5.56m)
        viewModel.addVertex(37.77495, -122.4194)
        val state2 = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        // Accidental tap within 8 meters suppressed!
        assertEquals(1, state2.vertices.size)

        // Tap distinct coordinate (approx 111m away: +0.001 deg lat):
        viewModel.addVertex(37.7759, -122.4194)
        val state3 = viewModel.geofenceEditorState.value as GeofenceEditorState.Editing
        assertEquals(2, state3.vertices.size)
    }

    @Test
    fun `cancelling new draft leaves saved geofences completely untouched`() = runTest {
        val originalFence = GeoFence.createCircle(
            id = "f_saved_1",
            name = "Existing Facility",
            center = GeoPoint2D(40.0, -74.0),
            radiusMeters = 300.0,
            colorArgb = 0xFF10B981.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(originalFence)
        testScheduler.advanceUntilIdle()

        // Start creating a new polygon and add vertices
        viewModel.startCreatingGeofence(GeoFenceType.POLYGON)
        viewModel.updateEditingName("Discarded Draft")
        viewModel.addVertex(10.0, 10.0)
        viewModel.addVertex(10.0, 11.0)
        viewModel.addVertex(11.0, 11.0)

        // Discard / Cancel editor
        viewModel.closeGeofenceEditor()
        testScheduler.advanceUntilIdle()

        // Editor state returns to Idle
        assertEquals(GeofenceEditorState.Idle, viewModel.geofenceEditorState.value)

        // Repository remains 100% intact with only the original fence
        val fences = geoFenceRepository.getGeoFences()
        assertEquals(1, fences.size)
        assertEquals("f_saved_1", fences.first().id)
        assertEquals("Existing Facility", fences.first().name)
    }

    @Test
    fun `cancelling edit to existing geofence does not overwrite saved geometry`() = runTest {
        val originalCircle = GeoFence.createCircle(
            id = "f_saved_circle",
            name = "Base Circle",
            center = GeoPoint2D(51.5074, -0.1278),
            radiusMeters = 500.0,
            colorArgb = 0xFF2563EB.toInt(),
            isEnabled = true
        )
        geoFenceRepository.addGeoFence(originalCircle)
        testScheduler.advanceUntilIdle()

        // Edit the existing fence
        viewModel.startEditingGeofence(originalCircle)
        // Alter radius, center, and name
        viewModel.updateEditingName("Modified Name")
        viewModel.setCircleCenter(52.0, 0.0)
        viewModel.setCircleRadius(1500.0)

        // User discards changes (cancels without saving)
        viewModel.closeGeofenceEditor()
        testScheduler.advanceUntilIdle()

        // Check that repository retained original unmodified circle
        val fences = geoFenceRepository.getGeoFences()
        assertEquals(1, fences.size)
        val saved = fences.first()
        assertEquals("f_saved_circle", saved.id)
        assertEquals("Base Circle", saved.name)
        assertEquals(51.5074, saved.center!!.latitude, 0.0001)
        assertEquals(-0.1278, saved.center!!.longitude, 0.0001)
        assertEquals(500.0, saved.radiusMeters ?: 0.0, 0.001)
    }

    @Test
    fun `selectedMarker updates coordinates reactively when new location arrives for selected device`() = runTest {
        val devId = TrackerId("tracker-1")
        val dev = TrackerDevice(
            deviceId = devId,
            displayName = "Tracker 1",
            registrationTimestamp = 1000L,
            connectionState = ConnectionState.CONNECTED
        )
        deviceRepository.registerDevice(dev)
        val initialLoc = Location(
            deviceId = devId,
            latitude = 23.8103,
            longitude = 90.4125,
            accuracy = 5f,
            bearing = 45f,
            speed = 10f,
            timestamp = 2000L,
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(initialLoc)
        testScheduler.advanceUntilIdle()

        viewModel.selectSingleDevice(devId)
        testScheduler.advanceUntilIdle()

        val marker1 = viewModel.selectedMarker.value
        assertNotNull(marker1)
        assertEquals(23.8103, marker1!!.latitude, 0.0001)
        assertEquals(90.4125, marker1.longitude, 0.0001)
        assertEquals(10f, marker1.speed!!, 0.01f)

        // New location update arrives for tracker-1
        val updatedLoc = Location(
            deviceId = devId,
            latitude = 23.8200,
            longitude = 90.4200,
            accuracy = 3f,
            bearing = 90f,
            speed = 15f,
            timestamp = 3000L,
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(updatedLoc)
        testScheduler.advanceUntilIdle()

        // selectedMarker must reactively reflect the updated position
        val marker2 = viewModel.selectedMarker.value
        assertNotNull(marker2)
        assertEquals(23.8200, marker2!!.latitude, 0.0001)
        assertEquals(90.4200, marker2.longitude, 0.0001)
        assertEquals(15f, marker2.speed!!, 0.01f)
    }

    @Test
    fun `selectSingleDevice on device without location does not fabricate coordinates and auto-focuses on first GPS fix`() = runTest {
        val devId = TrackerId("tracker-cold-start")
        val dev = TrackerDevice(
            deviceId = devId,
            displayName = "Cold Device",
            registrationTimestamp = 1000L,
            connectionState = ConnectionState.CONNECTED
        )
        deviceRepository.registerDevice(dev)
        testScheduler.advanceUntilIdle()

        // Device has no location yet
        viewModel.selectSingleDevice(devId)
        testScheduler.advanceUntilIdle()

        // Zero fabrication: selectedMarker must be null
        assertNull("Selected marker must not fabricate dummy coordinates for device with no fix", viewModel.selectedMarker.value)

        // First GPS fix arrives
        val firstFix = Location(
            deviceId = devId,
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 4f,
            bearing = 180f,
            speed = 0f,
            timestamp = 2000L,
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(firstFix)
        testScheduler.advanceUntilIdle()

        // Pending selection automatically locks onto the new marker
        val marker = viewModel.selectedMarker.value
        assertNotNull(marker)
        assertEquals(devId, marker!!.deviceId)
        assertEquals(37.7749, marker.latitude, 0.0001)
        assertEquals(-122.4194, marker.longitude, 0.0001)
    }

    @Test
    fun `selectedMarker is cleared when device is unregistered or map state becomes empty`() = runTest {
        val devId = TrackerId("tracker-to-remove")
        val dev = TrackerDevice(
            deviceId = devId,
            displayName = "Temporary Tracker",
            registrationTimestamp = 1000L,
            connectionState = ConnectionState.CONNECTED
        )
        deviceRepository.registerDevice(dev)
        val loc = Location(
            deviceId = devId,
            latitude = 40.7128,
            longitude = -74.0060,
            accuracy = 10f,
            bearing = 0f,
            speed = 0f,
            timestamp = 1000L,
            state = LocationState.LIVE
        )
        locationRepository.updateLocation(loc)
        testScheduler.advanceUntilIdle()

        viewModel.selectSingleDevice(devId)
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.selectedMarker.value)

        // Unregister device
        deviceRepository.unregisterDevice(devId)
        testScheduler.advanceUntilIdle()

        // Marker must be cleared
        assertNull(viewModel.selectedMarker.value)
    }

    @Test
    fun `selectMultipleDevices clears selectedMarker if focused marker is filtered out`() = runTest {
        val dev1 = TrackerId("dev-1")
        val dev2 = TrackerId("dev-2")
        deviceRepository.registerDevice(TrackerDevice(deviceId = dev1, displayName = "Dev 1", registrationTimestamp = 1000L, connectionState = ConnectionState.CONNECTED))
        deviceRepository.registerDevice(TrackerDevice(deviceId = dev2, displayName = "Dev 2", registrationTimestamp = 1000L, connectionState = ConnectionState.CONNECTED))
        locationRepository.updateLocation(Location(dev1, 10.0, 10.0, 5f, 0f, 0f, 1000L, LocationState.LIVE))
        locationRepository.updateLocation(Location(dev2, 20.0, 20.0, 5f, 0f, 0f, 1000L, LocationState.LIVE))
        testScheduler.advanceUntilIdle()

        viewModel.selectSingleDevice(dev1)
        testScheduler.advanceUntilIdle()
        assertEquals(dev1, viewModel.selectedMarker.value?.deviceId)

        // Change selection to only include dev2
        viewModel.selectMultipleDevices(setOf(dev2))
        testScheduler.advanceUntilIdle()

        // Focused marker dev1 is not in selection, must be cleared
        assertNull(viewModel.selectedMarker.value)
    }
}

