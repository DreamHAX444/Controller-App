package com.livetracker.controller.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleWakeWatchdogTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val sentCommands = mutableListOf<TrackerCommand>()

    private class FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = throw UnsupportedOperationException()
    }

    private fun createDevice(id: String, state: ConnectionState): TrackerDevice {
        return TrackerDevice(
            deviceId = TrackerId(id),
            displayName = "Tracker $id",
            connectionState = state,
            health = DeviceHealth.HEALTHY,
            registrationTimestamp = 1000L
        )
    }

    @Test
    fun `when app is minimized and timeout elapses, sends SLEEP to connected devices only`() = testScope.runTest {
        val repo = InMemoryTrackerDeviceRepository()
        repo.registerDevice(createDevice("tracker-1", ConnectionState.CONNECTED))
        repo.registerDevice(createDevice("tracker-2", ConnectionState.DISCONNECTED))
        repo.registerDevice(createDevice("tracker-3", ConnectionState.CONNECTED))

        val watchdog = IdleWakeWatchdog(
            trackerDeviceRepository = repo,
            sendCommand = { sentCommands.add(it) },
            scope = testScope,
            idleTimeoutMs = 5000L,
            dispatcher = testDispatcher
        )

        val owner = FakeLifecycleOwner()

        // App minimized
        watchdog.onStop(owner)

        // Before timeout: no commands dispatched
        advanceTimeBy(3000L)
        assertTrue(sentCommands.isEmpty())

        // After timeout: commands dispatched to connected devices
        advanceTimeBy(2500L)
        advanceUntilIdle()

        assertEquals(2, sentCommands.size)
        assertTrue(sentCommands[0] is TrackerCommand.Sleep)
        assertEquals(TrackerId("tracker-1"), sentCommands[0].deviceId)
        assertTrue(sentCommands[1] is TrackerCommand.Sleep)
        assertEquals(TrackerId("tracker-3"), sentCommands[1].deviceId)
    }

    @Test
    fun `when app is resumed before timeout elapses, cancels sleep countdown`() = testScope.runTest {
        val repo = InMemoryTrackerDeviceRepository()
        repo.registerDevice(createDevice("tracker-1", ConnectionState.CONNECTED))

        val watchdog = IdleWakeWatchdog(
            trackerDeviceRepository = repo,
            sendCommand = { sentCommands.add(it) },
            scope = testScope,
            idleTimeoutMs = 5000L,
            dispatcher = testDispatcher
        )

        val owner = FakeLifecycleOwner()

        // App minimized
        watchdog.onStop(owner)
        advanceTimeBy(3000L)

        // App resumed to foreground before 5s timeout
        watchdog.onStart(owner)

        // Time elapses further
        advanceTimeBy(10000L)
        advanceUntilIdle()

        // Countdown was cancelled, no commands should be sent
        assertTrue(sentCommands.isEmpty())
    }
}
