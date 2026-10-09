package com.livetracker.controller.core.tracker.location.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InMemoryTrackerLocationRepositoryTest {

    private fun createLocation(deviceId: TrackerId = TrackerId("dev1"), lat: Double = 10.0, timestamp: Long = 1000L, state: LocationState = LocationState.LIVE) = Location(
        deviceId = deviceId,
        latitude = lat,
        longitude = 20.0,
        accuracy = 5.0f,
        bearing = null,
        speed = null,
        timestamp = timestamp,
        state = state
    )

    // REPOSITORY TESTS
    @Test
    fun `insert and observe location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id = TrackerId("dev1")
        val loc = createLocation(deviceId = id)
        repo.updateLocation(loc)

        val record = repo.getLocation(id).first()
        assertNotNull(record)
        assertEquals(loc, record)
        assertEquals(LocationState.LIVE, record?.state)
    }

    @Test
    fun `update existing location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id = TrackerId("dev1")
        
        repo.updateLocation(createLocation(deviceId = id, lat = 10.0))
        repo.updateLocation(createLocation(deviceId = id, lat = 20.0, state = LocationState.STALE))

        val record = repo.getLocation(id).first()
        assertEquals(20.0, record?.latitude!!, 0.0)
        assertEquals(LocationState.STALE, record.state)
    }

    @Test
    fun `remove location`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id = TrackerId("dev1")
        repo.updateLocation(createLocation(deviceId = id))
        repo.clearLocation(id)

        assertNull(repo.getLocation(id).first())
    }

    @Test
    fun `clear all locations`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id1 = TrackerId("dev1")
        val id2 = TrackerId("dev2")
        repo.updateLocation(createLocation(deviceId = id1))
        repo.updateLocation(createLocation(deviceId = id2))
        repo.clearLocation(id1)
        repo.clearLocation(id2)

        assertTrue(repo.getAllLocations().first().isEmpty())
    }

    // MULTI-DEVICE ISOLATION TESTS
    @Test
    fun `two trackers maintain independent locations`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val idA = TrackerId("A")
        val idB = TrackerId("B")
        
        repo.updateLocation(createLocation(deviceId = idA, lat = 10.0))
        repo.updateLocation(createLocation(deviceId = idB, lat = 20.0, state = LocationState.STALE))

        assertEquals(10.0, repo.getLocation(idA).first()?.latitude!!, 0.0)
        assertEquals(LocationState.LIVE, repo.getLocation(idA).first()?.state)
        
        assertEquals(20.0, repo.getLocation(idB).first()?.latitude!!, 0.0)
        assertEquals(LocationState.STALE, repo.getLocation(idB).first()?.state)
    }

    @Test
    fun `removing A does not affect B`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val idA = TrackerId("A")
        val idB = TrackerId("B")
        
        repo.updateLocation(createLocation(deviceId = idA))
        repo.updateLocation(createLocation(deviceId = idB))
        repo.clearLocation(idA)

        assertNull(repo.getLocation(idA).first())
        assertNotNull(repo.getLocation(idB).first())
    }
    
    @Test
    fun `state update for A does not affect B`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val idA = TrackerId("A")
        val idB = TrackerId("B")
        
        repo.updateLocation(createLocation(deviceId = idA))
        repo.updateLocation(createLocation(deviceId = idB))
        
        repo.updateLocationState(idA, LocationState.OFFLINE)

        assertEquals(LocationState.OFFLINE, repo.getLocation(idA).first()?.state)
        assertEquals(LocationState.LIVE, repo.getLocation(idB).first()?.state)
    }

    // CONCURRENCY TESTS
    @Test
    fun `concurrent updates handle gracefully`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id = TrackerId("dev1")
        
        val jobs = (1..100).map { i ->
            async(Dispatchers.Default) {
                repo.updateLocation(createLocation(deviceId = id, timestamp = i.toLong()))
            }
        }
        jobs.awaitAll()
        
        assertNotNull(repo.getLocation(id).first())
    }

    @Test
    fun `concurrent updates to multiple devices`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val ids = (1..100).map { TrackerId("dev$it") }
        
        val jobs = ids.map { id ->
            async(Dispatchers.Default) {
                repo.updateLocation(createLocation(deviceId = id))
            }
        }
        jobs.awaitAll()
        
        assertEquals(100, repo.getAllLocations().first().size)
    }

    @Test
    fun `update and remove race condition`() = runTest {
        val repo = InMemoryTrackerLocationRepository()
        val id = TrackerId("dev1")
        
        val jobs = (1..100).map { i ->
            async(Dispatchers.Default) {
                if (i % 2 == 0) {
                    repo.updateLocation(createLocation(deviceId = id))
                } else {
                    repo.clearLocation(id)
                }
            }
        }
        jobs.awaitAll()
        // No crash means success
    }
}
