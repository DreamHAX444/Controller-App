package com.livetracker.controller

import com.livetracker.controller.core.tracker.device.data.DefaultDeviceManager
import com.livetracker.controller.core.tracker.device.data.DefaultDeviceSelectionManager
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceRepository
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.location.data.LocationData
import com.livetracker.controller.core.tracker.location.domain.LocationFreshnessPolicy
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.location.domain.ProcessLocationUpdateUseCase
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.core.tracker.map.domain.MapState
import com.livetracker.controller.core.tracker.map.domain.ObserveMapStateUseCase
import com.livetracker.controller.core.tracker.session.DefaultTrackerSessionManager
import com.livetracker.controller.core.tracker.supabase.SupabaseTrackerTransport
import com.livetracker.controller.ui.screens.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndLocationPipelineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tracker location acquired, transmitted, decoded, stored, and displayed on map UI`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val geoFenceRepo = InMemoryGeoFenceRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(
            repository = locationRepo,
            evaluateGeoFencesUseCase = null,
            policy = LocationFreshnessPolicy(futureToleranceMs = 10_000L)
        )

        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(
            deviceRepository = deviceRepo,
            locationRepository = locationRepo
        )

        val observeMapStateUseCase = ObserveMapStateUseCase(
            observeDeviceSummaries = observeDeviceSummariesUseCase,
            selectionManager = selectionManager
        )

        val viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = geoFenceRepo,
            deviceSelectionManager = selectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.mapState.collect {}
        }

        // 1. Initial State is empty or initializing
        advanceUntilIdle()
        val initialMapState = viewModel.mapState.value
        assertTrue(initialMapState is MapState.Initializing || initialMapState is MapState.Empty)

        // 2. Simulated tracker transmits JSON payload matching tracker_app LiveLocationService
        val wireJson = """
            {
                "device_id": "Pixel_10_Pro_Tracker",
                "latitude": 23.8103,
                "longitude": 90.4125,
                "altitude": 18.2,
                "speed": 8.5,
                "accuracy": 3.8,
                "bearing": 120.0,
                "battery_level": 0.95,
                "charging": false,
                "signal_dbm": -68,
                "network_type": "4G LTE",
                "created_at": "2026-10-15T12:00:00.000Z"
            }
        """.trimIndent()

        // 3. Controller decodes and validates incoming message
        val decoded = json.decodeFromString<LocationData>(wireJson)
        assertTrue(decoded.isValidCoordinates())
        assertEquals("Pixel_10_Pro_Tracker", decoded.deviceId)

        val trackerId = TrackerId(decoded.deviceId)

        // Register device and sync metadata
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerId,
                displayName = decoded.deviceId,
                registrationTimestamp = System.currentTimeMillis(),
                connectionState = ConnectionState.CONNECTED,
                batteryLevel = (decoded.batteryLevel!! * 100).toInt(),
                networkState = decoded.networkType
            )
        )
        selectionManager.selectAll()

        // 4. Ingest location event into state store via ProcessLocationUpdateUseCase
        val locationEvent = decoded.toLocationUpdatedEvent()
        val fixedCurrentTime = decoded.parseTimestamp() + 1000L // 1s later -> LIVE
        processLocationUpdateUseCase(locationEvent, currentTimeMs = fixedCurrentTime)

        advanceUntilIdle()

        // 5. Verify Controller state store updated
        val storedLocation = locationRepo.getLocation(trackerId).first()
        assertNotNull(storedLocation)
        assertEquals(23.8103, storedLocation!!.latitude, 0.0001)
        assertEquals(90.4125, storedLocation.longitude, 0.0001)
        assertEquals(3.8f, storedLocation.accuracy!!, 0.01f)
        assertEquals(120.0f, storedLocation.bearing!!, 0.01f)
        assertEquals(8.5f, storedLocation.speed!!, 0.01f)
        assertEquals(LocationState.LIVE, storedLocation.state)

        // 6. Verify MapViewModel exposes updated MapMarker
        val mapState = viewModel.mapState.value
        assertTrue("Expected MapState.Active but got: $mapState", mapState is MapState.Active)
        val active = mapState as MapState.Active
        assertEquals(1, active.markers.size)

        val marker = active.markers[0]
        assertEquals(trackerId, marker.deviceId)
        assertEquals("Pixel_10_Pro_Tracker", marker.displayName)
        assertEquals(23.8103, marker.latitude, 0.0001)
        assertEquals(90.4125, marker.longitude, 0.0001)
        assertEquals(3.8f, marker.accuracy!!, 0.01f)
        assertEquals(LocationState.LIVE, marker.locationState)
        assertEquals(ConnectionState.CONNECTED, marker.connectionState)
        assertEquals(95, marker.batteryLevel)
        assertEquals("4G LTE", marker.networkState)

        // 7. Verify out-of-order update is rejected
        val staleJson = wireJson.replace("2026-10-15T12:00:00.000Z", "2026-10-15T11:00:00.000Z")
            .replace("23.8103", "10.0000")
        val staleData = json.decodeFromString<LocationData>(staleJson)
        processLocationUpdateUseCase(staleData.toLocationUpdatedEvent(), currentTimeMs = fixedCurrentTime)

        advanceUntilIdle()

        // Coordinates must NOT be overwritten by older update
        val afterStaleLoc = locationRepo.getLocation(trackerId).first()
        assertEquals(23.8103, afterStaleLoc!!.latitude, 0.0001)

        // 8. Verify invalid coordinates are rejected
        val invalidCoordData = decoded.copy(latitude = 95.0)
        assertFalse(invalidCoordData.isValidCoordinates())
        processLocationUpdateUseCase(invalidCoordData.toLocationUpdatedEvent(), currentTimeMs = fixedCurrentTime + 2000L)

        advanceUntilIdle()
        val afterInvalidLoc = locationRepo.getLocation(trackerId).first()
        assertEquals(23.8103, afterInvalidLoc!!.latitude, 0.0001)
    }

    @Test
    fun `disconnecting preserves last known location without fabricating data`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)
        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locationRepo)
        val observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, selectionManager)

        val trackerId = TrackerId("tracker-beta")
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerId,
                displayName = "Tracker Beta",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )

        val loc = LocationData(
            deviceId = "tracker-beta",
            latitude = 40.7580,
            longitude = -73.9855,
            accuracy = 5f,
            createdAt = "2026-10-15T12:00:00.000Z"
        )
        processLocationUpdateUseCase(loc.toLocationUpdatedEvent(), currentTimeMs = loc.parseTimestamp())

        // Disconnect device
        deviceRepo.updateDevice(trackerId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        advanceUntilIdle()

        val summaries = observeDeviceSummariesUseCase().first()
        assertEquals(1, summaries.size)
        assertEquals(ConnectionState.DISCONNECTED, summaries[0].device.connectionState)
        // Last known coordinates are intact
        assertEquals(40.7580, summaries[0].latestLocation!!.latitude, 0.0001)
    }

    @Test
    fun `malformed and incomplete JSON payloads are rejected without corrupting pipeline state`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)
        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locationRepo)
        val observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, selectionManager)
        val viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = InMemoryGeoFenceRepository(),
            deviceSelectionManager = selectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.mapState.collect {}
        }

        val trackerId = TrackerId("tracker-resilient")
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerId,
                displayName = "Tracker Resilient",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )
        selectionManager.selectAll()

        // Send valid initial position
        val validJson = """
            {
                "device_id": "tracker-resilient",
                "latitude": 34.0522,
                "longitude": -118.2437,
                "accuracy": 5.0,
                "created_at": "2026-10-15T10:00:00.000Z"
            }
        """.trimIndent()
        val validData = json.decodeFromString<LocationData>(validJson)
        processLocationUpdateUseCase(validData.toLocationUpdatedEvent(), currentTimeMs = validData.parseTimestamp())
        advanceUntilIdle()

        val initialLoc = locationRepo.getLocation(trackerId).first()
        assertNotNull(initialLoc)
        assertEquals(34.0522, initialLoc!!.latitude, 0.0001)

        // Attempt injecting malformed JSON strings
        val badPayloads = listOf(
            "{ malformed",
            """{"latitude": 10.0, "longitude": 20.0}""", // missing device_id & created_at
            """{"device_id": "", "latitude": 10.0, "longitude": 20.0, "created_at": "2026-10-15T10:00:00.000Z"}""", // empty device_id
            """{"device_id": "tracker-resilient", "latitude": 999.0, "longitude": 20.0, "created_at": "2026-10-15T10:00:00.000Z"}""" // out of bounds lat
        )

        for (bad in badPayloads) {
            try {
                val data = json.decodeFromString<LocationData>(bad)
                if (data.isValidCoordinates()) {
                    processLocationUpdateUseCase(data.toLocationUpdatedEvent(), currentTimeMs = data.parseTimestamp())
                }
            } catch (_: Exception) {
                // Expected parsing failures
            }
        }
        advanceUntilIdle()

        // Existing location in repository and UI marker must remain intact and uncorrupted
        val afterBadLoc = locationRepo.getLocation(trackerId).first()
        assertEquals(34.0522, afterBadLoc!!.latitude, 0.0001)
        assertEquals(-118.2437, afterBadLoc.longitude, 0.0001)
    }

    @Test
    fun `multiple device concurrent pipeline isolates telemetry and UI markers per device`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)
        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locationRepo)
        val observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, selectionManager)
        val viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = InMemoryGeoFenceRepository(),
            deviceSelectionManager = selectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.mapState.collect {}
        }

        val trackerA = TrackerId("tracker-alpha")
        val trackerB = TrackerId("tracker-beta")

        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerA,
                displayName = "Tracker Alpha",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerB,
                displayName = "Tracker Beta",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )
        selectionManager.selectAll()

        // Ingest telemetry for Tracker A (San Francisco)
        val locA = LocationData("tracker-alpha", 37.7749, -122.4194, accuracy = 4f, createdAt = "2026-10-15T12:00:00.000Z")
        processLocationUpdateUseCase(locA.toLocationUpdatedEvent(), currentTimeMs = locA.parseTimestamp())

        // Ingest telemetry for Tracker B (New York)
        val locB = LocationData("tracker-beta", 40.7128, -74.0060, accuracy = 5f, createdAt = "2026-10-15T12:00:00.000Z")
        processLocationUpdateUseCase(locB.toLocationUpdatedEvent(), currentTimeMs = locB.parseTimestamp())
        advanceUntilIdle()

        // Both markers visible when all selected
        val mapActive = viewModel.mapState.value as MapState.Active
        assertEquals(2, mapActive.markers.size)
        val markerA = mapActive.markers.find { it.deviceId == trackerA }!!
        val markerB = mapActive.markers.find { it.deviceId == trackerB }!!
        assertEquals(37.7749, markerA.latitude, 0.0001)
        assertEquals(40.7128, markerB.latitude, 0.0001)

        // Select only Tracker A -> Tracker A is selected, Tracker B is unselected
        selectionManager.selectDevice(trackerA)
        advanceUntilIdle()
        val filteredA = viewModel.mapState.value as MapState.Active
        assertEquals(2, filteredA.markers.size)
        assertTrue(filteredA.markers.find { it.deviceId == trackerA }!!.isSelected)
        assertFalse(filteredA.markers.find { it.deviceId == trackerB }!!.isSelected)

        // Select only Tracker B -> Tracker B is selected, Tracker A is unselected
        selectionManager.selectDevice(trackerB)
        advanceUntilIdle()
        val filteredB = viewModel.mapState.value as MapState.Active
        assertEquals(2, filteredB.markers.size)
        assertTrue(filteredB.markers.find { it.deviceId == trackerB }!!.isSelected)
        assertFalse(filteredB.markers.find { it.deviceId == trackerA }!!.isSelected)

        // Tracker A updates location while Tracker B is selected -> Tracker B position unaffected
        val locA2 = LocationData("tracker-alpha", 37.8000, -122.4000, accuracy = 3f, createdAt = "2026-10-15T12:05:00.000Z")
        processLocationUpdateUseCase(locA2.toLocationUpdatedEvent(), currentTimeMs = locA2.parseTimestamp())
        advanceUntilIdle()

        val repoB = locationRepo.getLocation(trackerB).first()
        assertEquals(40.7128, repoB!!.latitude, 0.0001)
        val repoA = locationRepo.getLocation(trackerA).first()
        assertEquals(37.8000, repoA!!.latitude, 0.0001)
    }

    @Test
    fun `reconnection cycle restores live tracking with new coordinates while preserving history`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)
        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locationRepo)
        val observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, selectionManager)
        val viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = InMemoryGeoFenceRepository(),
            deviceSelectionManager = selectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.mapState.collect {}
        }

        val trackerId = TrackerId("tracker-cycle")
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerId,
                displayName = "Tracker Cycle",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )
        selectionManager.selectAll()

        // 1. Initial live location
        val loc1 = LocationData("tracker-cycle", 51.5074, -0.1278, accuracy = 3f, createdAt = "2026-10-15T12:00:00.000Z")
        val t1 = loc1.parseTimestamp()
        processLocationUpdateUseCase(loc1.toLocationUpdatedEvent(), currentTimeMs = t1)
        advanceUntilIdle()

        var marker = (viewModel.mapState.value as MapState.Active).markers[0]
        assertEquals(ConnectionState.CONNECTED, marker.connectionState)
        assertEquals(51.5074, marker.latitude, 0.0001)

        // 2. Disconnect
        deviceRepo.updateDevice(trackerId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        advanceUntilIdle()
        marker = (viewModel.mapState.value as MapState.Active).markers[0]
        assertEquals(ConnectionState.DISCONNECTED, marker.connectionState)
        assertEquals(51.5074, marker.latitude, 0.0001) // preserved!

        // 3. Reconnect
        deviceRepo.updateDevice(trackerId) { it.copy(connectionState = ConnectionState.CONNECTED) }
        advanceUntilIdle()
        marker = (viewModel.mapState.value as MapState.Active).markers[0]
        assertEquals(ConnectionState.CONNECTED, marker.connectionState)
        assertEquals(51.5074, marker.latitude, 0.0001)

        // 4. Fresh location arrives post-reconnect
        val loc2 = LocationData("tracker-cycle", 51.5100, -0.1300, accuracy = 2.5f, createdAt = "2026-10-15T12:05:00.000Z")
        val t2 = loc2.parseTimestamp()
        processLocationUpdateUseCase(loc2.toLocationUpdatedEvent(), currentTimeMs = t2)
        advanceUntilIdle()

        marker = (viewModel.mapState.value as MapState.Active).markers[0]
        assertEquals(51.5100, marker.latitude, 0.0001)
        assertEquals(LocationState.LIVE, marker.locationState)
    }

    @Test
    fun `rapid repeated reconnection cycles under continuous telemetry updates maintain consistent UI state without duplicate markers`() = runTest {
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val selectionManager = DefaultDeviceSelectionManager()

        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)
        val observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locationRepo)
        val observeMapStateUseCase = ObserveMapStateUseCase(observeDeviceSummariesUseCase, selectionManager)
        val viewModel = MapViewModel(
            observeMapStateUseCase = observeMapStateUseCase,
            geoFenceRepository = InMemoryGeoFenceRepository(),
            deviceSelectionManager = selectionManager,
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.mapState.collect {}
        }

        val trackerId = TrackerId("tracker-stress-recon")
        deviceRepo.registerDevice(
            com.livetracker.controller.core.tracker.device.domain.TrackerDevice(
                deviceId = trackerId,
                displayName = "Tracker Stress",
                registrationTimestamp = 1000L,
                connectionState = ConnectionState.CONNECTED
            )
        )
        selectionManager.selectAll()

        // 5 rapid connect-disconnect cycles with interspersed telemetry
        for (i in 1..5) {
            deviceRepo.updateDevice(trackerId) { it.copy(connectionState = ConnectionState.DISCONNECTED) }
            advanceUntilIdle()

            deviceRepo.updateDevice(trackerId) { it.copy(connectionState = ConnectionState.CONNECTED) }
            val loc = LocationData(
                deviceId = "tracker-stress-recon",
                latitude = 20.0 + i * 0.1,
                longitude = 30.0 + i * 0.1,
                accuracy = 4f,
                createdAt = "2026-10-15T12:%02d:00.000Z".format(i)
            )
            processLocationUpdateUseCase(loc.toLocationUpdatedEvent(), currentTimeMs = loc.parseTimestamp())
            advanceUntilIdle()

            val state = viewModel.mapState.value as MapState.Active
            assertEquals(1, state.markers.size)
            assertEquals(trackerId, state.markers[0].deviceId)
            assertEquals(ConnectionState.CONNECTED, state.markers[0].connectionState)
            assertEquals(20.0 + i * 0.1, state.markers[0].latitude, 0.001)
        }
    }
}

