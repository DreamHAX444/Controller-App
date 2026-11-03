package com.livetracker.controller.core.tracker.location.domain

import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessLocationUpdateUseCaseTest {

    private fun createEvent(
        deviceId: TrackerId = TrackerId("dev1"),
        timestamp: Long = 1000L,
        lat: Double = 10.0,
        lng: Double = 20.0,
        acc: Float = 5.0f,
        bearing: Float? = null,
        speed: Float? = null
    ) = TrackerEvent.LocationUpdated("evt1", deviceId, timestamp, lat, lng, acc, bearing, speed)

    // VALIDATION TESTS
    @Test
    fun `process valid location update stores live location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        val event = createEvent(timestamp = 1000L)
        useCase(event, currentTimeMs = 1000L)
        val loc = repo.getLocation(TrackerId("dev1")).first()
        assertNotNull(loc)
        assertEquals(LocationState.LIVE, loc!!.state)
    }

    @Test
    fun `rejects latitude too high`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(lat = 91.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects latitude too low`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(lat = -91.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects longitude too high`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(lng = 181.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects longitude too low`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(lng = -181.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects negative and zero accuracy`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        
        // Exact zero accuracy
        useCase(createEvent(acc = 0.0f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())

        // Negative decimal accuracy
        useCase(createEvent(acc = -0.001f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())

        // Negative accuracy
        useCase(createEvent(acc = -1.0f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects non-positive timestamp`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 0L), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())

        useCase(createEvent(timestamp = -100L), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects negative speed`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(speed = -5.0f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects NaN coordinates`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(lat = Double.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())

        useCase(createEvent(lng = Double.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects NaN or Infinite accuracy`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(acc = Float.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())

        useCase(createEvent(acc = Float.POSITIVE_INFINITY), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `normalizes bearing to standard range`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(bearing = 450f), currentTimeMs = 1000L)
        val loc = repo.getLocation(TrackerId("dev1")).first()
        assertNotNull(loc)
        assertEquals(90f, loc!!.bearing!!, 0.01f)
    }

    @Test
    fun `rejects future timestamp beyond tolerance`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy(futureToleranceMs = 5000))
        useCase(createEvent(timestamp = 20000L), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `accepts future timestamp within tolerance`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy(futureToleranceMs = 5000))
        useCase(createEvent(timestamp = 4000L), currentTimeMs = 1000L)
        assertNotNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects missing deviceId`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        val event = TrackerEvent.LocationUpdated("evt", TrackerId(""), 1000L, 10.0, 20.0, 5.0f)
        useCase(event, currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("")).first())
    }

    // ORDERING TESTS
    @Test
    fun `accepts ordered updates`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 1000L)
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `rejects older out of order update`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 3000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `rejects duplicate update`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        useCase(createEvent(timestamp = 2000L, lat = 30.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `late event from disconnected tracker is safely rejected if old`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 5000L, lat = 20.0), currentTimeMs = 5000L)
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 6000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    // FRESHNESS TESTS
    @Test
    fun `process old location update stores stale location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val policy = LocationFreshnessPolicy(staleThresholdMs = 15000, offlineThresholdMs = 60000)
        val useCase = ProcessLocationUpdateUseCase(repo, null, policy)
        useCase(createEvent(timestamp = 1000L), currentTimeMs = 20000L)
        assertEquals(LocationState.STALE, repo.getLocation(TrackerId("dev1")).first()?.state)
    }

    @Test
    fun `process very old location update stores offline location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val policy = LocationFreshnessPolicy(staleThresholdMs = 15000, offlineThresholdMs = 60000)
        val useCase = ProcessLocationUpdateUseCase(repo, null, policy)
        useCase(createEvent(timestamp = 1000L), currentTimeMs = 70000L)
        assertEquals(LocationState.OFFLINE, repo.getLocation(TrackerId("dev1")).first()?.state)
    }

    @Test
    fun `processes multiple devices independently`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        val dev1 = TrackerId("dev1")
        val dev2 = TrackerId("dev2")
        useCase(createEvent(deviceId = dev1, lat = 10.0), currentTimeMs = 1000L)
        useCase(createEvent(deviceId = dev2, lat = 20.0), currentTimeMs = 1000L)
        
        assertEquals(10.0, repo.getLocation(dev1).first()?.latitude!!, 0.0)
        assertEquals(20.0, repo.getLocation(dev2).first()?.latitude!!, 0.0)
    }

    @Test
    fun `accepts exact valid boundary coordinates`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        
        // Exact 90.0, 180.0, 0.001f accuracy
        useCase(createEvent(deviceId = TrackerId("north-east"), lat = 90.0, lng = 180.0, acc = 0.001f), currentTimeMs = 1000L)
        val ne = repo.getLocation(TrackerId("north-east")).first()
        assertNotNull(ne)
        assertEquals(90.0, ne!!.latitude, 0.0)
        assertEquals(180.0, ne.longitude, 0.0)
        assertEquals(0.001, ne.accuracy!!.toDouble(), 0.0001)

        // Exact -90.0, -180.0
        useCase(createEvent(deviceId = TrackerId("south-west"), lat = -90.0, lng = -180.0, acc = 0.001f), currentTimeMs = 1000L)
        val sw = repo.getLocation(TrackerId("south-west")).first()
        assertNotNull(sw)
        assertEquals(-90.0, sw!!.latitude, 0.0)
        assertEquals(-180.0, sw.longitude, 0.0)
    }


    @Test
    fun `rejects NaN and infinite coordinates`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())

        useCase(createEvent(deviceId = TrackerId("nan-lat"), lat = Double.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("nan-lat")).first())

        useCase(createEvent(deviceId = TrackerId("nan-lng"), lng = Double.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("nan-lng")).first())

        useCase(createEvent(deviceId = TrackerId("inf-lat"), lat = Double.POSITIVE_INFINITY), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("inf-lat")).first())

        useCase(createEvent(deviceId = TrackerId("neg-inf-lng"), lng = Double.NEGATIVE_INFINITY), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("neg-inf-lng")).first())
    }

    @Test
    fun `rapid sequential updates maintain latest valid position`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        val dev = TrackerId("dev-rapid")

        for (i in 1..10) {
            useCase(
                createEvent(deviceId = dev, timestamp = 1000L + i * 100, lat = 10.0 + i, lng = 20.0 + i),
                currentTimeMs = 1000L + i * 100
            )
        }

        val finalLoc = repo.getLocation(dev).first()
        assertNotNull(finalLoc)
        assertEquals(20.0, finalLoc!!.latitude, 0.0)
        assertEquals(30.0, finalLoc.longitude, 0.0)
        assertEquals(2000L, finalLoc.timestamp)
    }

    @Test
    fun `multiple device isolation during interleaved updates with partial failures`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())
        val devA = TrackerId("device-A")
        val devB = TrackerId("device-B")
        val devC = TrackerId("device-C")

        // Interleaved valid events
        useCase(createEvent(deviceId = devA, timestamp = 1000L, lat = 10.0, lng = 10.0), currentTimeMs = 1000L)
        useCase(createEvent(deviceId = devB, timestamp = 1000L, lat = 20.0, lng = 20.0), currentTimeMs = 1000L)
        useCase(createEvent(deviceId = devC, timestamp = 1000L, lat = 30.0, lng = 30.0), currentTimeMs = 1000L)

        // Invalid event for Dev B (e.g. out of bounds lat)
        useCase(createEvent(deviceId = devB, timestamp = 2000L, lat = 120.0, lng = 25.0), currentTimeMs = 2000L)

        // Valid subsequent event for Dev A
        useCase(createEvent(deviceId = devA, timestamp = 2000L, lat = 11.0, lng = 11.0), currentTimeMs = 2000L)

        // Out of order event for Dev C
        useCase(createEvent(deviceId = devC, timestamp = 500L, lat = 29.0, lng = 29.0), currentTimeMs = 2000L)

        // Verify Device A moved to 11.0
        val locA = repo.getLocation(devA).first()
        assertEquals(11.0, locA!!.latitude, 0.0)
        assertEquals(2000L, locA.timestamp)

        // Verify Device B stayed at 1000L / 20.0 (invalid update rejected)
        val locB = repo.getLocation(devB).first()
        assertEquals(20.0, locB!!.latitude, 0.0)
        assertEquals(1000L, locB.timestamp)

        // Verify Device C stayed at 1000L / 30.0 (stale update rejected)
        val locC = repo.getLocation(devC).first()
        assertEquals(30.0, locC!!.latitude, 0.0)
        assertEquals(1000L, locC.timestamp)
    }

    @Test
    fun `rejects non-finite speed and bearing`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy())

        // Non-finite speed
        useCase(createEvent(deviceId = TrackerId("spd-nan"), speed = Float.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("spd-nan")).first())

        useCase(createEvent(deviceId = TrackerId("spd-inf"), speed = Float.POSITIVE_INFINITY), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("spd-inf")).first())

        // Non-finite bearing
        useCase(createEvent(deviceId = TrackerId("brg-nan"), bearing = Float.NaN), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("brg-nan")).first())

        useCase(createEvent(deviceId = TrackerId("brg-inf"), bearing = Float.POSITIVE_INFINITY), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("brg-inf")).first())
    }

    @Test
    fun `burst of interleaved out-of-order and duplicate packets maintains strictly monotonic state`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, null, LocationFreshnessPolicy(futureToleranceMs = 10_000L))
        val dev = TrackerId("dev-burst")

        // Monotonic progression: 1000 -> 2000 -> 3000 -> 4000
        useCase(createEvent(deviceId = dev, timestamp = 1000L, lat = 10.0), currentTimeMs = 1000L)
        assertEquals(10.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Inject duplicate packet with different coordinates
        useCase(createEvent(deviceId = dev, timestamp = 1000L, lat = 99.0), currentTimeMs = 1000L)
        assertEquals(10.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Valid newer packet
        useCase(createEvent(deviceId = dev, timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Inject older out-of-order packet (timestamp 1500L < 2000L)
        useCase(createEvent(deviceId = dev, timestamp = 1500L, lat = 50.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Valid newer packet
        useCase(createEvent(deviceId = dev, timestamp = 3000L, lat = 30.0), currentTimeMs = 3000L)
        assertEquals(30.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Out-of-order from distant past (timestamp 500L)
        useCase(createEvent(deviceId = dev, timestamp = 500L, lat = 1.0), currentTimeMs = 3000L)
        assertEquals(30.0, repo.getLocation(dev).first()!!.latitude, 0.0)

        // Valid final packet
        useCase(createEvent(deviceId = dev, timestamp = 4000L, lat = 40.0), currentTimeMs = 4000L)
        val finalLoc = repo.getLocation(dev).first()
        assertEquals(40.0, finalLoc!!.latitude, 0.0)
        assertEquals(4000L, finalLoc.timestamp)
    }
}

