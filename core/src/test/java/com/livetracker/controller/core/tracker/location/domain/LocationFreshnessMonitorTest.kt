package com.livetracker.controller.core.tracker.location.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationFreshnessMonitorTest {

    @Test
    fun `monitor updates state to stale when time passes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(dispatcher)
        val repo = InMemoryTrackerLocationRepository()
        
        // Very short thresholds for testing
        val policy = LocationFreshnessPolicy(staleThresholdMs = 2000, offlineThresholdMs = 5000)
        
        val monitor = LocationFreshnessMonitor(
            repository = repo,
            policy = policy,
            scope = testScope,
            checkIntervalMs = 1000L
        )
        
        val deviceId = TrackerId("dev1")
        val loc = Location(
            deviceId = deviceId,
            latitude = 1.0,
            longitude = 2.0,
            accuracy = 3f,
            bearing = null,
            speed = null,
            timestamp = System.currentTimeMillis(), // "Now"
            state = LocationState.LIVE
        )
        
        repo.updateLocation(loc)
        monitor.start()
        
        // Advance time by 3 seconds, should become STALE
        advanceTimeBy(3000)
        var currentLoc = repo.getLocation(deviceId).first()
        assertEquals(LocationState.STALE, currentLoc?.state)
        
        // Advance time by another 3 seconds (6 total), should become OFFLINE
        advanceTimeBy(3000)
        currentLoc = repo.getLocation(deviceId).first()
        assertEquals(LocationState.OFFLINE, currentLoc?.state)
        
        monitor.stop()
    }
}
