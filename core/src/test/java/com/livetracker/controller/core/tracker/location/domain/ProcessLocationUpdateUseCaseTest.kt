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
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        val event = createEvent(timestamp = 1000L)
        useCase(event, currentTimeMs = 1000L)
        val loc = repo.getLocation(TrackerId("dev1")).first()
        assertNotNull(loc)
        assertEquals(LocationState.LIVE, loc!!.state)
    }

    @Test
    fun `rejects latitude too high`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(lat = 91.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects latitude too low`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(lat = -91.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects longitude too high`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(lng = 181.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects longitude too low`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(lng = -181.0), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects negative accuracy`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(acc = -1.0f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects negative speed`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(speed = -5.0f), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects future timestamp beyond tolerance`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy(futureToleranceMs = 5000))
        useCase(createEvent(timestamp = 20000L), currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `accepts future timestamp within tolerance`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy(futureToleranceMs = 5000))
        useCase(createEvent(timestamp = 4000L), currentTimeMs = 1000L)
        assertNotNull(repo.getLocation(TrackerId("dev1")).first())
    }

    @Test
    fun `rejects missing deviceId`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        val event = TrackerEvent.LocationUpdated("evt", TrackerId(""), 1000L, 10.0, 20.0, 5.0f)
        useCase(event, currentTimeMs = 1000L)
        assertNull(repo.getLocation(TrackerId("")).first())
    }

    // ORDERING TESTS
    @Test
    fun `accepts ordered updates`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 1000L)
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `rejects older out of order update`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 3000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `rejects duplicate update`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 2000L, lat = 20.0), currentTimeMs = 2000L)
        useCase(createEvent(timestamp = 2000L, lat = 30.0), currentTimeMs = 2000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    @Test
    fun `late event from disconnected tracker is safely rejected if old`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val useCase = ProcessLocationUpdateUseCase(repo, LocationFreshnessPolicy())
        useCase(createEvent(timestamp = 5000L, lat = 20.0), currentTimeMs = 5000L)
        useCase(createEvent(timestamp = 1000L, lat = 10.0), currentTimeMs = 6000L)
        assertEquals(20.0, repo.getLocation(TrackerId("dev1")).first()?.latitude!!, 0.0)
    }

    // FRESHNESS TESTS
    @Test
    fun `process old location update stores stale location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val policy = LocationFreshnessPolicy(staleThresholdMs = 15000, offlineThresholdMs = 60000)
        val useCase = ProcessLocationUpdateUseCase(repo, policy)
        useCase(createEvent(timestamp = 1000L), currentTimeMs = 20000L)
        assertEquals(LocationState.STALE, repo.getLocation(TrackerId("dev1")).first()?.state)
    }

    @Test
    fun `process very old location update stores offline location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val policy = LocationFreshnessPolicy(staleThresholdMs = 15000, offlineThresholdMs = 60000)
        val useCase = ProcessLocationUpdateUseCase(repo, policy)
        useCase(createEvent(timestamp = 1000L), currentTimeMs = 70000L)
        assertEquals(LocationState.OFFLINE, repo.getLocation(TrackerId("dev1")).first()?.state)
    }
}
