package com.livetracker.controller.core.tracker.location.domain

import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessPolicyTest {

    private val policy = LocationFreshnessPolicy(
        staleThresholdMs = 15_000L,
        offlineThresholdMs = 60_000L,
        futureToleranceMs = 5_000L
    )

    @Test
    fun `evaluate returns LIVE for fresh timestamp`() {
        val now = 100_000L
        assertEquals(LocationState.LIVE, policy.evaluate(locationTimestamp = now, currentTimeMs = now))
        assertEquals(LocationState.LIVE, policy.evaluate(locationTimestamp = now - 10_000L, currentTimeMs = now))
        assertEquals(LocationState.LIVE, policy.evaluate(locationTimestamp = now - 15_000L, currentTimeMs = now))
        assertTrue(policy.isLive(now - 5_000L, now))
        assertFalse(policy.isStale(now - 5_000L, now))
        assertFalse(policy.isOffline(now - 5_000L, now))
    }

    @Test
    fun `evaluate returns LIVE for future timestamp within tolerance`() {
        val now = 100_000L
        assertEquals(LocationState.LIVE, policy.evaluate(locationTimestamp = now + 4_000L, currentTimeMs = now))
        assertEquals(LocationState.LIVE, policy.evaluate(locationTimestamp = now + 5_000L, currentTimeMs = now))
        assertTrue(policy.isLive(now + 2_000L, now))
    }

    @Test
    fun `evaluate returns UNKNOWN for future timestamp beyond tolerance`() {
        val now = 100_000L
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = now + 5_001L, currentTimeMs = now))
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = now + 20_000L, currentTimeMs = now))
        assertFalse(policy.isLive(now + 10_000L, now))
    }

    @Test
    fun `evaluate returns STALE between stale and offline threshold`() {
        val now = 100_000L
        assertEquals(LocationState.STALE, policy.evaluate(locationTimestamp = now - 15_001L, currentTimeMs = now))
        assertEquals(LocationState.STALE, policy.evaluate(locationTimestamp = now - 45_000L, currentTimeMs = now))
        assertEquals(LocationState.STALE, policy.evaluate(locationTimestamp = now - 60_000L, currentTimeMs = now))
        assertTrue(policy.isStale(now - 30_000L, now))
    }

    @Test
    fun `evaluate returns OFFLINE beyond offline threshold`() {
        val now = 1_000_000L
        assertEquals(LocationState.OFFLINE, policy.evaluate(locationTimestamp = now - 60_001L, currentTimeMs = now))
        assertEquals(LocationState.OFFLINE, policy.evaluate(locationTimestamp = now - 120_000L, currentTimeMs = now))
        assertTrue(policy.isOffline(now - 90_000L, now))
    }

    @Test
    fun `evaluate returns UNKNOWN for non-positive timestamps`() {
        val now = 100_000L
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = 0L, currentTimeMs = now))
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = -500L, currentTimeMs = now))
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = now, currentTimeMs = 0L))
        assertEquals(LocationState.UNKNOWN, policy.evaluate(locationTimestamp = now, currentTimeMs = -100L))
    }

    @Test
    fun `formatElapsedSwift returns exact seconds and minutes without Just now`() {
        val now = 1_000_000_000L
        assertEquals("Never", formatElapsedSwift(null, now))
        assertEquals("Never", formatElapsedSwift(0L, now))
        assertEquals("Never", formatElapsedSwift(-10L, now))

        // Seconds level (0s to 59s)
        assertEquals("0s", formatElapsedSwift(now, now))
        assertEquals("1s", formatElapsedSwift(now - 1_000L, now))
        assertEquals("5s", formatElapsedSwift(now - 5_000L, now))
        assertEquals("30s", formatElapsedSwift(now - 30_000L, now))
        assertEquals("59s", formatElapsedSwift(now - 59_000L, now))

        // Minutes level (1m 0s to 59m 59s)
        assertEquals("1m 0s", formatElapsedSwift(now - 60_000L, now))
        assertEquals("1m 15s", formatElapsedSwift(now - 75_000L, now))
        assertEquals("25m 40s", formatElapsedSwift(now - (25 * 60 + 40) * 1_000L, now))

        // Hours level (1h 0m to 23h 59m)
        assertEquals("1h 0m", formatElapsedSwift(now - 3600_000L, now))
        assertEquals("2h 15m", formatElapsedSwift(now - (2 * 3600 + 15 * 60) * 1_000L, now))

        // Days level
        assertEquals("1d 2h", formatElapsedSwift(now - (26 * 3600) * 1_000L, now))
    }
}
