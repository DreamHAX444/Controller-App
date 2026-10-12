package com.livetracker.controller.core.tracker.geofence.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DistanceCalculatorTest {

    @Test
    fun `distance between same points is 0`() {
        val distance = DistanceCalculator.calculateDistanceMeters(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, distance, 0.1)
    }

    @Test
    fun `distance between known points is accurate`() {
        // San Francisco (37.7749, -122.4194) to San Jose (37.3382, -121.8863)
        // Approx 68 km
        val distance = DistanceCalculator.calculateDistanceMeters(37.7749, -122.4194, 37.3382, -121.8863)
        val expected = 68000.0 // 68 km roughly
        // We allow 2km tolerance because earth radius varies and this is simple Haversine
        assertTrue("Distance should be around 68km, was ${distance/1000}km", abs(distance - expected) < 2000.0)
    }
}
