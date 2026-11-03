package com.livetracker.controller.ui.screens.map.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests validating Phase 3 SmoothMarkerAnimator interpolation math,
 * shortest angular delta logic, and boundary condition handling.
 */
class SmoothMarkerAnimatorTest {

    @Test
    fun shortestAngleDelta_crossingNorthClockwise_computesShortestPositiveAngle() {
        // Crossing North from 350° to 10° should be +20°, not -340°
        val delta = SmoothMarkerAnimator.shortestAngleDelta(350f, 10f)
        assertEquals(20f, delta, 0.001f)
    }

    @Test
    fun shortestAngleDelta_crossingNorthCounterClockwise_computesShortestNegativeAngle() {
        // Crossing North from 10° to 350° should be -20°, not +340°
        val delta = SmoothMarkerAnimator.shortestAngleDelta(10f, 350f)
        assertEquals(-20f, delta, 0.001f)
    }

    @Test
    fun shortestAngleDelta_straightAngles_computesExactArc() {
        assertEquals(90f, SmoothMarkerAnimator.shortestAngleDelta(0f, 90f), 0.001f)
        assertEquals(-90f, SmoothMarkerAnimator.shortestAngleDelta(90f, 0f), 0.001f)
        assertEquals(0f, SmoothMarkerAnimator.shortestAngleDelta(180f, 180f), 0.001f)
        assertEquals(-180f, SmoothMarkerAnimator.shortestAngleDelta(0f, 180f), 0.001f)
    }

    @Test
    fun lerp_boundaryFractions_returnsExactEndpoints() {
        assertEquals(10.0, SmoothMarkerAnimator.lerp(10.0, 20.0, 0.0), 0.0001)
        assertEquals(20.0, SmoothMarkerAnimator.lerp(10.0, 20.0, 1.0), 0.0001)
        assertEquals(15.0, SmoothMarkerAnimator.lerp(10.0, 20.0, 0.5), 0.0001)
    }

    @Test
    fun shortestAngleDelta_neverExceeds180Degrees() {
        val testPairs = listOf(
            0f to 359f,
            45f to 270f,
            120f to 300f,
            315f to 45f
        )
        for ((from, to) in testPairs) {
            val delta = abs(SmoothMarkerAnimator.shortestAngleDelta(from, to))
            assertTrue("Delta between $from and $to was $delta (> 180)", delta <= 180f)
        }
    }
}
