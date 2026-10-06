package com.livetracker.controller.core.tracker.domain

import com.livetracker.controller.core.tracker.session.DefaultTrackerSessionManager
import com.livetracker.controller.core.tracker.session.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerSessionManagerHeartbeatTest {

    private val fakeTransports = mutableMapOf<TrackerId, FakeTrackerTransport>()

    private class TestTimeSource(val testScope: TestScope) : TimeSource {
        override fun currentTimeMillis(): Long = testScope.testScheduler.currentTime
    }

    private fun TestScope.setupManager(policy: HeartbeatPolicy = HeartbeatPolicy()): DefaultTrackerSessionManager {
        fakeTransports.clear()
        val timeSource = TestTimeSource(this)
        return DefaultTrackerSessionManager(backgroundScope, policy, timeSource) { deviceId ->
            val transport = FakeTrackerTransport(deviceId)
            fakeTransports[deviceId] = transport
            transport
        }
    }

    private fun runTestEagerly(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        block()
    }

    // 1. Initial heartbeat state
    @Test
    fun `Initial heartbeat state is UNKNOWN`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertNotNull(state)
        assertEquals(HeartbeatHealth.UNKNOWN, state?.health)
        assertNull(state?.lastHeartbeatTimestamp)
        assertEquals(0, state?.missedHeartbeatCount)
    }

    // 2. First heartbeat marks Tracker healthy
    @Test
    fun `First heartbeat marks Tracker healthy`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(HeartbeatHealth.HEALTHY, state?.health)
        assertEquals(1, state?.sequence)
    }

    // 3. Heartbeat updates timestamp
    @Test
    fun `Heartbeat updates timestamp`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        val t1 = testScheduler.currentTime
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, t1, 1))
        
        advanceTimeBy(5000)
        runCurrent()
        val t2 = testScheduler.currentTime
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, t2, 2))
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(t2, state?.lastHeartbeatTimestamp)
    }

    // 4. Heartbeat sequence increases correctly
    @Test
    fun `Heartbeat sequence increases correctly`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        advanceTimeBy(1000)
        runCurrent()
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, testScheduler.currentTime, 2))
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(2, state?.sequence)
    }

    // 5. Duplicate heartbeat does not incorrectly reset state
    @Test
    fun `Duplicate heartbeat does not incorrectly reset state`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        val state1 = manager.getSession(id)?.value?.heartbeatState
        
        // Emitting the exact same heartbeat
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, testScheduler.currentTime, 1))
        
        val state2 = manager.getSession(id)?.value?.heartbeatState
        assertEquals(state1, state2)
    }

    // 6. Out-of-order heartbeat is handled safely
    @Test
    fun `Out-of-order heartbeat is ignored safely`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        val t1 = testScheduler.currentTime
        advanceTimeBy(5000)
        runCurrent()
        val t2 = testScheduler.currentTime
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, t2, 2))
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, t1, 1))
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(t2, state?.lastHeartbeatTimestamp)
        assertEquals(2, state?.sequence)
    }

    // 7. Healthy remains healthy within threshold
    @Test
    fun `Healthy remains healthy within threshold`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        advanceTimeBy(15_001)
        runCurrent()
        runCurrent()
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(HeartbeatHealth.HEALTHY, state?.health)
        assertEquals(1, state?.missedHeartbeatCount)
    }

    // 8. Healthy becomes degraded after threshold
    @Test
    fun `Healthy becomes degraded after threshold`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        advanceTimeBy(45_000)
        runCurrent()
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(HeartbeatHealth.DEGRADED, state?.health)
        assertEquals(3, state?.missedHeartbeatCount)
    }

    // 9. Degraded becomes lost after loss threshold
    @Test
    fun `Degraded becomes lost after loss threshold`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        advanceTimeBy(90_000)
        runCurrent()
        
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(HeartbeatHealth.LOST, state?.health)
        assertEquals(6, state?.missedHeartbeatCount)
    }

    // 10. Lost Tracker recovers after valid heartbeat
    @Test
    fun `Lost Tracker recovers after valid heartbeat`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(HeartbeatHealth.LOST, manager.getSession(id)?.value?.heartbeatState?.health)
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, testScheduler.currentTime, 2))
        assertEquals(HeartbeatHealth.HEALTHY, manager.getSession(id)?.value?.heartbeatState?.health)
    }

    // 11. Recovery resets missed count
    @Test
    fun `Recovery resets missed count`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals(3, manager.getSession(id)?.value?.heartbeatState?.missedHeartbeatCount)
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, testScheduler.currentTime, 2))
        assertEquals(0, manager.getSession(id)?.value?.heartbeatState?.missedHeartbeatCount)
    }

    // 12. Old heartbeat cannot revive a newer lost sequence incorrectly
    @Test
    fun `Old heartbeat cannot revive a newer lost sequence incorrectly`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        val t1 = testScheduler.currentTime
        advanceTimeBy(5000)
        runCurrent()
        val t2 = testScheduler.currentTime
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id, t2, 2))
        advanceTimeBy(100_000)
        runCurrent() // Becomes LOST
        
        assertEquals(HeartbeatHealth.LOST, manager.getSession(id)?.value?.heartbeatState?.health)
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, t1, 1))
        
        assertEquals(HeartbeatHealth.LOST, manager.getSession(id)?.value?.heartbeatState?.health)
    }

    // 13. Tracker A health does not affect Tracker B
    @Test
    fun `Tracker A health does not affect Tracker B`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id1, testScheduler.currentTime, 1))
        fakeTransports[id2]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id2, testScheduler.currentTime, 1))
        
        advanceTimeBy(45_000)
        runCurrent() // both degraded
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", id1, testScheduler.currentTime, 2))
        
        assertEquals(HeartbeatHealth.HEALTHY, manager.getSession(id1)?.value?.heartbeatState?.health)
        assertEquals(HeartbeatHealth.DEGRADED, manager.getSession(id2)?.value?.heartbeatState?.health)
    }

    // 14. Three Trackers can have different health states
    @Test
    fun `Three Trackers can have different health states`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val idA = TrackerId("t1")
        val idB = TrackerId("t2")
        val idC = TrackerId("t3")
        
        manager.registerTracker(idA, "TA")
        manager.registerTracker(idB, "TB")
        manager.registerTracker(idC, "TC")
        
        val start = testScheduler.currentTime
        fakeTransports[idA]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", idA, start, 1))
        fakeTransports[idB]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", idB, start, 1))
        fakeTransports[idC]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", idC, start, 1))
        
        advanceTimeBy(45_000)
        runCurrent()
        
        fakeTransports[idB]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", idB, testScheduler.currentTime, 2))
        
        advanceTimeBy(45_000)
        runCurrent()
        
        fakeTransports[idA]?.emitEvent(TrackerEvent.HeartbeatReceived("e2", idA, testScheduler.currentTime, 2))
        
        assertEquals(HeartbeatHealth.HEALTHY, manager.getSession(idA)?.value?.heartbeatState?.health)
        assertEquals(HeartbeatHealth.DEGRADED, manager.getSession(idB)?.value?.heartbeatState?.health)
        assertEquals(HeartbeatHealth.LOST, manager.getSession(idC)?.value?.heartbeatState?.health)
    }

    // 15. Unregister cancels heartbeat monitoring
    @Test
    fun `Unregister cancels heartbeat monitoring`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        manager.unregisterTracker(id)
        assertFalse(manager.hasSession(id))
        
        advanceTimeBy(90_000)
        runCurrent() // Should not crash
    }

    // 16. Late heartbeat after unregister is ignored
    @Test
    fun `Late heartbeat after unregister is ignored`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        manager.unregisterTracker(id)
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        assertFalse(manager.hasSession(id))
    }

    // 17. Re-register creates fresh heartbeat state
    @Test
    fun `Re-register creates fresh heartbeat state`() = runTestEagerly {
        val manager = setupManager(HeartbeatPolicy(15_000, 45_000, 90_000))
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        manager.unregisterTracker(id)
        
        manager.registerTracker(id, "T1")
        val state = manager.getSession(id)?.value?.heartbeatState
        assertEquals(HeartbeatHealth.UNKNOWN, state?.health)
        assertEquals(0, state?.sequence)
    }

    // 19. Configurable heartbeat policy works
    @Test
    fun `Configurable heartbeat policy works`() = runTestEagerly {
        val policy = HeartbeatPolicy(1000, 3000, 6000)
        val manager = setupManager(policy)
        val id = TrackerId("t1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.emitEvent(TrackerEvent.HeartbeatReceived("e1", id, testScheduler.currentTime, 1))
        
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(HeartbeatHealth.DEGRADED, manager.getSession(id)?.value?.heartbeatState?.health)
        
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(HeartbeatHealth.LOST, manager.getSession(id)?.value?.heartbeatState?.health)
    }
}
