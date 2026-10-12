package com.livetracker.controller.core.tracker.geofence.domain

import com.livetracker.controller.core.tracker.alert.data.InMemoryAlertRepository
import com.livetracker.controller.core.tracker.alert.domain.AlertType
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceRepository
import com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceStateRepository
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvaluateGeoFencesUseCaseTest {

    private lateinit var geoFenceRepository: InMemoryGeoFenceRepository
    private lateinit var geoFenceStateRepository: InMemoryGeoFenceStateRepository
    private lateinit var alertRepository: InMemoryAlertRepository
    private lateinit var useCase: EvaluateGeoFencesUseCase

    private val deviceId = TrackerId("tracker-1")
    private val polygonVertices = listOf(
        GeoPoint2D(0.0, 0.0),
        GeoPoint2D(0.0, 10.0),
        GeoPoint2D(10.0, 10.0),
        GeoPoint2D(10.0, 0.0)
    )
    
    private val geoFence1 = GeoFence(
        id = "gf-1",
        name = "Square Zone",
        vertices = polygonVertices,
        colorArgb = 0,
        isEnabled = true
    )

    @Before
    fun setup() {
        geoFenceRepository = InMemoryGeoFenceRepository()
        geoFenceStateRepository = InMemoryGeoFenceStateRepository()
        alertRepository = InMemoryAlertRepository()
        useCase = EvaluateGeoFencesUseCase(geoFenceRepository, geoFenceStateRepository, alertRepository)
    }

    @Test
    fun `when no geo-fences exist, no alerts are triggered`() = runTest {
        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `when device moves inside polygon geo-fence, triggers GEOFENCE_ENTER alert`() = runTest {
        geoFenceRepository.addGeoFence(geoFence1)
        geoFenceStateRepository.updateState(deviceId, geoFence1.id, GeoFenceState.OUTSIDE)

        // Inside the polygon (5.0, 5.0 is inside 0..10, 0..10)
        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, geoFence1.id))
    }
    
    @Test
    fun `when device is disabled geo-fence, no alerts are triggered`() = runTest {
        geoFenceRepository.addGeoFence(geoFence1.copy(isEnabled = false))
        geoFenceStateRepository.updateState(deviceId, geoFence1.id, GeoFenceState.OUTSIDE)

        // Inside the polygon but fence disabled
        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `when device starts UNKNOWN and moves inside, no alert is triggered`() = runTest {
        // By default state is UNKNOWN
        geoFenceRepository.addGeoFence(geoFence1)

        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, geoFence1.id))
    }

    @Test
    fun `when device moves outside polygon geo-fence, triggers GEOFENCE_EXIT alert`() = runTest {
        geoFenceRepository.addGeoFence(geoFence1)
        geoFenceStateRepository.updateState(deviceId, geoFence1.id, GeoFenceState.INSIDE)

        // Move outside (15.0, 15.0 is outside)
        val location = Location(deviceId, 15.0, 15.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_EXIT, alerts[0].type)
        assertEquals(GeoFenceState.OUTSIDE, geoFenceStateRepository.getState(deviceId, geoFence1.id))
    }

    @Test
    fun `when device stays inside polygon geo-fence, no duplicate alerts`() = runTest {
        geoFenceRepository.addGeoFence(geoFence1)
        geoFenceStateRepository.updateState(deviceId, geoFence1.id, GeoFenceState.INSIDE)

        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
    }
}
