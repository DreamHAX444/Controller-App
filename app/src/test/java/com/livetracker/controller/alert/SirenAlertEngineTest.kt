package com.livetracker.controller.alert

import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SirenAlertEngineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `sound profiles have valid non-empty display names`() {
        AlertSoundProfile.entries.forEach { profile ->
            assertTrue(profile.displayName.isNotBlank())
        }
        assertEquals(5, AlertSoundProfile.entries.size)
    }

    @Test
    fun `activeAlarm state tracks properties correctly`() {
        val state = ActiveAlarmState(
            alertId = "alert-100",
            geofenceId = "gf-1",
            geofenceName = "Restricted Area",
            deviceId = "tracker-alpha",
            soundProfile = AlertSoundProfile.TACTICAL_SIREN,
            durationSeconds = 30,
            startedAtMs = 1000L,
            timeRemainingSeconds = 30
        )

        assertEquals("alert-100", state.alertId)
        assertEquals("gf-1", state.geofenceId)
        assertEquals("Restricted Area", state.geofenceName)
        assertEquals("tracker-alpha", state.deviceId)
        assertEquals(AlertSoundProfile.TACTICAL_SIREN, state.soundProfile)
        assertEquals(30, state.durationSeconds)
        assertEquals(30, state.timeRemainingSeconds)
    }
}
