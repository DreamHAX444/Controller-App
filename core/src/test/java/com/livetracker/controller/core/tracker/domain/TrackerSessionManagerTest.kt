package com.livetracker.controller.core.tracker.domain

import com.livetracker.controller.core.tracker.session.DefaultTrackerSessionManager
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerSessionManagerTest {

    private val fakeTransports = mutableMapOf<TrackerId, FakeTrackerTransport>()

    private fun TestScope.setupManager(): DefaultTrackerSessionManager {
        fakeTransports.clear()
        return DefaultTrackerSessionManager(backgroundScope) { deviceId ->
            val transport = FakeTrackerTransport(deviceId)
            fakeTransports[deviceId] = transport
            transport
        }
    }

    private fun runTestEagerly(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        block()
    }

    // REGISTRY
    @Test
    fun `Register Tracker`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-1")
        manager.registerTracker(id, "T1")
        assertTrue(manager.hasSession(id))
    }

    @Test
    fun `Duplicate registration rejected`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-1")
        manager.registerTracker(id, "T1")
        
        fakeTransports[id]?.setConnectionState(ConnectionState.CONNECTING)
        
        manager.registerTracker(id, "T2")
        val session = manager.getSession(id)
        assertEquals("T1", session?.value?.displayName)
        assertEquals(ConnectionState.CONNECTING, session?.value?.connectionState)
    }

    @Test
    fun `Retrieve Tracker`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-1")
        manager.registerTracker(id, "T1")
        val session = manager.getSession(id)
        assertNotNull(session)
        assertEquals("T1", session?.value?.displayName)
    }

    @Test
    fun `Retrieve unknown Tracker`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-unknown")
        assertNull(manager.getSession(id))
        assertFalse(manager.hasSession(id))
    }

    @Test
    fun `Unregister Tracker`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-1")
        manager.registerTracker(id, "T1")
        manager.unregisterTracker(id)
        assertFalse(manager.hasSession(id))
    }

    @Test
    fun `Unregister unknown Tracker does not crash`() = runTestEagerly {
        val manager = setupManager()
        val id = TrackerId("tracker-unknown")
        manager.unregisterTracker(id) // Should not throw
    }

    @Test
    fun `List multiple Trackers`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        val sessions = manager.getAllSessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.value.deviceId == id1 })
        assertTrue(sessions.any { it.value.deviceId == id2 })
        
        val list = manager.listSessions()
        assertEquals(2, list.size)
        assertTrue(list.containsAll(listOf(id1, id2)))
    }

    @Test
    fun `clearSessions removes all`() = runTestEagerly {
        val manager = setupManager()
        manager.registerTracker(TrackerId("t1"), "T1")
        manager.registerTracker(TrackerId("t2"), "T2")
        manager.clearSessions()
        assertEquals(0, manager.listSessions().size)
    }

    // ISOLATION
    @Test
    fun `Two Trackers maintain independent states`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTED)
        
        assertEquals(ConnectionState.CONNECTING, manager.getSession(id1)?.value?.connectionState)
        assertEquals(ConnectionState.CONNECTED, manager.getSession(id2)?.value?.connectionState)
    }

    @Test
    fun `Three Trackers maintain independent states`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        val id3 = TrackerId("t3")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        manager.registerTracker(id3, "T3")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTED)
        
        // id3 stays disconnected
        
        assertEquals(ConnectionState.CONNECTING, manager.getSession(id1)?.value?.connectionState)
        assertEquals(ConnectionState.CONNECTED, manager.getSession(id2)?.value?.connectionState)
        assertEquals(ConnectionState.DISCONNECTED, manager.getSession(id3)?.value?.connectionState)
    }

    @Test
    fun `Disconnecting A does not affect B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTED)
        
        manager.disconnect(id1)
        fakeTransports[id1]?.setConnectionState(ConnectionState.DISCONNECTED)
        
        assertEquals(ConnectionState.DISCONNECTED, manager.getSession(id1)?.value?.connectionState)
        assertEquals(ConnectionState.CONNECTED, manager.getSession(id2)?.value?.connectionState)
    }

    @Test
    fun `Removing A does not affect B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        manager.unregisterTracker(id1)
        
        assertFalse(manager.hasSession(id1))
        assertTrue(manager.hasSession(id2))
    }

    @Test
    fun `Error in A does not affect B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.Error("e1", id1, 0, SessionError.PROTOCOL_ERROR))
        
        assertNotNull(manager.getSession(id1)?.value?.lastError)
        assertNull(manager.getSession(id2)?.value?.lastError)
    }

    @Test
    fun `Capability update in A does not affect B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e1", id1, 0, TrackerCapabilities(location = true)))
        
        assertTrue(manager.getSession(id1)?.value?.capabilities?.location == true)
        assertFalse(manager.getSession(id2)?.value?.capabilities?.location == true)
    }

    // COMMANDS
    @Test
    fun `Command A routes only to A`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e1", id1, 0, TrackerCapabilities(location = true)))
        fakeTransports[id2]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e2", id2, 0, TrackerCapabilities(location = true)))
        
        val result = manager.sendCommand(TrackerCommand.RequestLocation("c1", id1, 0, "user", 5000))
        
        assertTrue(result is CommandResult.Completed)
        assertEquals(id1, result.deviceId)
    }

    @Test
    fun `Command B routes only to B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id2]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e1", id1, 0, TrackerCapabilities(location = true)))
        fakeTransports[id2]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e2", id2, 0, TrackerCapabilities(location = true)))
        
        val result = manager.sendCommand(TrackerCommand.RequestLocation("c2", id2, 0, "user", 5000))
        
        assertTrue(result is CommandResult.Completed)
        assertEquals(id2, result.deviceId)
    }

    @Test
    fun `Unknown device returns SESSION_NOT_FOUND`() = runTestEagerly {
        val manager = setupManager()
        val result = manager.sendCommand(TrackerCommand.RequestLocation("c1", TrackerId("t3"), 0, "user", 5000))
        assertTrue(result is CommandResult.Failed)
        assertEquals("Session not found", (result as CommandResult.Failed).reason)
    }

    @Test
    fun `Disconnected device does not receive command`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        
        val result = manager.sendCommand(TrackerCommand.RequestLocation("c1", id1, 0, "user", 5000))
        assertTrue(result is CommandResult.Failed)
        assertEquals("Device not connected", (result as CommandResult.Failed).reason)
    }

    @Test
    fun `Command result remains associated with correct device`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTING)
        fakeTransports[id1]?.setConnectionState(ConnectionState.CONNECTED)
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e1", id1, 0, TrackerCapabilities(location = true)))
        
        val result = manager.sendCommand(TrackerCommand.RequestLocation("c1", id1, 0, "user", 5000))
        assertTrue(result is CommandResult.Completed)
        assertEquals(id1, result.deviceId)
    }

    // EVENTS
    @Test
    fun `Event A updates only A`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id1]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e1", id1, 0, TrackerCapabilities(camera = true)))
        assertTrue(manager.getSession(id1)?.value?.capabilities?.camera == true)
        assertFalse(manager.getSession(id2)?.value?.capabilities?.camera == true)
    }

    @Test
    fun `Event B updates only B`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        val id2 = TrackerId("t2")
        manager.registerTracker(id1, "T1")
        manager.registerTracker(id2, "T2")
        
        fakeTransports[id2]?.emitEvent(TrackerEvent.CapabilitiesUpdated("e2", id2, 0, TrackerCapabilities(camera = true)))
        assertFalse(manager.getSession(id1)?.value?.capabilities?.camera == true)
        assertTrue(manager.getSession(id2)?.value?.capabilities?.camera == true)
    }

    @Test
    fun `Unknown-device event is rejected`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        
        // Emitting an event with id2 on transport1
        fakeTransports[id1]?.emitEvent(TrackerEvent.Error("e1", TrackerId("t2"), 0, SessionError.PROTOCOL_ERROR))
        
        assertNull(manager.getSession(id1)?.value?.lastError)
    }

    @Test
    fun `Late event from removed Tracker is ignored`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        val transport = fakeTransports[id1]!!
        
        manager.unregisterTracker(id1)
        
        transport.emitEvent(TrackerEvent.Error("e1", id1, 0, SessionError.PROTOCOL_ERROR))
        assertFalse(manager.hasSession(id1))
    }

    // LIFECYCLE
    @Test
    fun `Register, connect, disconnect`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        val transport = fakeTransports[id1]!!
        
        transport.setConnectionState(ConnectionState.CONNECTING)
        transport.setConnectionState(ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, manager.getSession(id1)?.value?.connectionState)
        
        transport.setConnectionState(ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, manager.getSession(id1)?.value?.connectionState)
    }

    @Test
    fun `Register, unregister`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        assertTrue(manager.hasSession(id1))
        manager.unregisterTracker(id1)
        assertFalse(manager.hasSession(id1))
    }

    @Test
    fun `Unregister, re-register works cleanly`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        manager.unregisterTracker(id1)
        
        manager.registerTracker(id1, "T1-new")
        assertEquals("T1-new", manager.getSession(id1)?.value?.displayName)
    }

    @Test
    fun `Reconnect after disconnect does not create duplicate session`() = runTestEagerly {
        val manager = setupManager()
        val id1 = TrackerId("t1")
        manager.registerTracker(id1, "T1")
        
        val transport = fakeTransports[id1]!!
        transport.setConnectionState(ConnectionState.CONNECTED)
        transport.setConnectionState(ConnectionState.DISCONNECTED)
        
        assertEquals(1, manager.listSessions().size)
        manager.startConnection(id1)
        assertEquals(1, manager.listSessions().size)
    }

    // CONCURRENCY
    @Test
    fun `Concurrent register unregister operations do not corrupt registry`() = runTestEagerly {
        val manager = setupManager()
        val ids = (1..100).map { TrackerId("t$it") }
        
        val jobs = ids.map { id ->
            launch {
                manager.registerTracker(id, "T")
                manager.unregisterTracker(id)
                manager.registerTracker(id, "T")
            }
        }
        jobs.joinAll()
        kotlinx.coroutines.delay(10)
        
        assertEquals(100, manager.listSessions().size)
    }

    @Test
    fun `Concurrent events for different Trackers remain isolated`() = runTestEagerly {
        val manager = setupManager()
        val ids = (1..100).map { TrackerId("t$it") }
        
        ids.forEach { manager.registerTracker(it, "T") }
        
        val jobs = ids.map { id ->
            launch {
                fakeTransports[id]?.emitEvent(TrackerEvent.Error("e1", id, 0, SessionError.PROTOCOL_ERROR))
            }
        }
        jobs.joinAll()
        kotlinx.coroutines.delay(10)
        
        ids.forEach { id ->
            assertNotNull(manager.getSession(id)?.value?.lastError)
        }
    }
}
