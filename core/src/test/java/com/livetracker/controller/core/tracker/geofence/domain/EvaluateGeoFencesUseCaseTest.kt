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
    fun `when device starts UNKNOWN and moves inside, triggers GEOFENCE_ENTER alert if rule permits`() = runTest {
        // By default state is UNKNOWN and rule is BOTH
        geoFenceRepository.addGeoFence(geoFence1)

        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
        assertEquals("gf-1", alerts[0].geofenceId)
        assertEquals(30, alerts[0].durationSeconds)
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, geoFence1.id))
    }

    @Test
    fun `when device starts UNKNOWN and is inside but rule is EXIT only, no alert is triggered`() = runTest {
        val exitOnlyFence = geoFence1.copy(triggerRule = GeofenceTriggerRule.EXIT)
        geoFenceRepository.addGeoFence(exitOnlyFence)

        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, exitOnlyFence.id))
    }

    @Test
    fun `when rule is EXIT only and device moves inside, no enter alert is triggered`() = runTest {
        val exitOnlyFence = geoFence1.copy(triggerRule = GeofenceTriggerRule.EXIT)
        geoFenceRepository.addGeoFence(exitOnlyFence)
        geoFenceStateRepository.updateState(deviceId, exitOnlyFence.id, GeoFenceState.OUTSIDE)

        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `when rule is ENTRY only and device moves outside, no exit alert is triggered`() = runTest {
        val entryOnlyFence = geoFence1.copy(triggerRule = GeofenceTriggerRule.ENTRY)
        geoFenceRepository.addGeoFence(entryOnlyFence)
        geoFenceStateRepository.updateState(deviceId, entryOnlyFence.id, GeoFenceState.INSIDE)

        val location = Location(deviceId, 15.0, 15.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
        assertEquals(GeoFenceState.OUTSIDE, geoFenceStateRepository.getState(deviceId, entryOnlyFence.id))
    }

    @Test
    fun `when geofence has specific targetDeviceId, other devices are ignored`() = runTest {
        val targetFence = geoFence1.copy(targetDeviceId = TrackerId("tracker-vip"))
        geoFenceRepository.addGeoFence(targetFence)
        geoFenceStateRepository.updateState(deviceId, targetFence.id, GeoFenceState.OUTSIDE)

        // deviceId is "tracker-1", target is "tracker-vip"
        val location = Location(deviceId, 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(location)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())

        // VIP device should trigger
        val vipLocation = Location(TrackerId("tracker-vip"), 5.0, 5.0, 10f, null, null, 1000L, LocationState.LIVE)
        geoFenceStateRepository.updateState(TrackerId("tracker-vip"), targetFence.id, GeoFenceState.OUTSIDE)
        useCase(vipLocation)

        val vipAlerts = alertRepository.getUnacknowledgedAlerts(TrackerId("tracker-vip"))
        assertEquals(1, vipAlerts.size)
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

    // ==========================================
    // CIRCLE GEOFENCE TESTS
    // ==========================================

    private val circleCenter = GeoPoint2D(37.7749, -122.4194)
    private val circleFence = GeoFence.createCircle(
        id = "circle-1",
        name = "Market Street Safe Circle",
        center = circleCenter,
        radiusMeters = 500.0,
        colorArgb = 0xFF2563EB.toInt(),
        isEnabled = true
    )

    @Test
    fun `when device moves inside circle geofence, triggers GEOFENCE_ENTER alert`() = runTest {
        geoFenceRepository.addGeoFence(circleFence)
        geoFenceStateRepository.updateState(deviceId, circleFence.id, GeoFenceState.OUTSIDE)

        // Point ~100m north of center (inside 500m radius)
        // 1 degree latitude ~ 111,000m => 0.001 deg ~ 111m
        val insideLoc = Location(deviceId, 37.7758, -122.4194, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(insideLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, circleFence.id))
    }

    @Test
    fun `when device is exactly at circle center, evaluates as INSIDE and triggers enter`() = runTest {
        geoFenceRepository.addGeoFence(circleFence)
        geoFenceStateRepository.updateState(deviceId, circleFence.id, GeoFenceState.OUTSIDE)

        val centerLoc = Location(deviceId, circleCenter.latitude, circleCenter.longitude, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(centerLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, circleFence.id))
    }

    @Test
    fun `when device is exactly on circle radius boundary, evaluates as INSIDE`() = runTest {
        val center = GeoPoint2D(0.0, 0.0)
        val boundaryLoc = Location(deviceId, 0.001, 0.0, 10f, null, null, 1000L, LocationState.LIVE)
        val exactRadius = DistanceCalculator.calculateDistanceMeters(
            center.latitude, center.longitude,
            boundaryLoc.latitude, boundaryLoc.longitude
        )
        val boundaryFence = GeoFence.createCircle(
            id = "boundary-circle",
            name = "Equator Circle",
            center = center,
            radiusMeters = exactRadius,
            colorArgb = 0
        )
        geoFenceRepository.addGeoFence(boundaryFence)
        geoFenceStateRepository.updateState(deviceId, boundaryFence.id, GeoFenceState.OUTSIDE)

        useCase(boundaryLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
    }

    @Test
    fun `when device moves outside circle geofence, triggers GEOFENCE_EXIT alert`() = runTest {
        geoFenceRepository.addGeoFence(circleFence)
        geoFenceStateRepository.updateState(deviceId, circleFence.id, GeoFenceState.INSIDE)

        // Point ~1500m away (outside 500m radius)
        val outsideLoc = Location(deviceId, 37.7900, -122.4194, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(outsideLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_EXIT, alerts[0].type)
        assertEquals(GeoFenceState.OUTSIDE, geoFenceStateRepository.getState(deviceId, circleFence.id))
    }

    @Test
    fun `when device stays inside circle geofence across repeated updates, no duplicate alerts`() = runTest {
        geoFenceRepository.addGeoFence(circleFence)
        geoFenceStateRepository.updateState(deviceId, circleFence.id, GeoFenceState.INSIDE)

        val loc1 = Location(deviceId, 37.7750, -122.4194, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(loc1)
        val loc2 = Location(deviceId, 37.7752, -122.4194, 10f, null, null, 2000L, LocationState.LIVE)
        useCase(loc2)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, circleFence.id))
    }

    @Test
    fun `when circle geofence is disabled, no alerts are triggered`() = runTest {
        geoFenceRepository.addGeoFence(circleFence.copy(isEnabled = false))
        geoFenceStateRepository.updateState(deviceId, circleFence.id, GeoFenceState.OUTSIDE)

        val insideLoc = Location(deviceId, 37.7750, -122.4194, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(insideLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `when circle is near antimeridian, distance calculation and transitions are accurate`() = runTest {
        // Center near longitude 179.999 deg
        val antimeridianCenter = GeoPoint2D(0.0, 179.999)
        val antimeridianFence = GeoFence.createCircle(
            id = "antimeridian-circle",
            name = "Date Line Zone",
            center = antimeridianCenter,
            radiusMeters = 1000.0,
            colorArgb = 0
        )
        geoFenceRepository.addGeoFence(antimeridianFence)
        geoFenceStateRepository.updateState(deviceId, antimeridianFence.id, GeoFenceState.OUTSIDE)

        // Point crossed across 180 to -179.999 (angular distance 0.002 deg ~ 222m)
        val acrossDateLineLoc = Location(deviceId, 0.0, -179.999, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(acrossDateLineLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
        assertEquals(GeoFenceState.INSIDE, geoFenceStateRepository.getState(deviceId, antimeridianFence.id))
    }

    @Test
    fun `when circle is at high latitude, evaluation works correctly`() = runTest {
        val polarCenter = GeoPoint2D(80.0, 0.0)
        val polarFence = GeoFence.createCircle(
            id = "polar-circle",
            name = "Arctic Circle Zone",
            center = polarCenter,
            radiusMeters = 5000.0,
            colorArgb = 0
        )
        geoFenceRepository.addGeoFence(polarFence)
        geoFenceStateRepository.updateState(deviceId, polarFence.id, GeoFenceState.OUTSIDE)

        // Point ~1km north
        val polarLoc = Location(deviceId, 80.009, 0.0, 10f, null, null, 1000L, LocationState.LIVE)
        useCase(polarLoc)

        val alerts = alertRepository.getUnacknowledgedAlerts(deviceId)
        assertEquals(1, alerts.size)
        assertEquals(AlertType.GEOFENCE_ENTER, alerts[0].type)
    }
}
