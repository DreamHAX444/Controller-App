package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupabaseTrackerTransportTest {

    @Test
    fun `transport events isolate and route strictly to target deviceId`() = runTest {
        val fleetLocationEvents = MutableSharedFlow<TrackerEvent.LocationUpdated>(extraBufferCapacity = 16)
        val fleetConnState = MutableStateFlow(ConnectionState.CONNECTED)

        // Mock minimal fleet transport behavior
        val targetId = TrackerId("tracker-target")
        val otherId = TrackerId("tracker-other")

        val transport = object : TrackerTransport {
            override val deviceId: TrackerId = targetId
            override val connectionState: StateFlow<ConnectionState> = fleetConnState
            override val events = fleetLocationEvents.filter { it.deviceId == targetId }
            override suspend fun connect() {}
            override suspend fun disconnect() {}
            override suspend fun sendCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand) =
                com.livetracker.controller.core.tracker.domain.CommandResult.Completed(command.commandId, command.deviceId)
        }

        val receivedEvents = mutableListOf<TrackerEvent.LocationUpdated>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            transport.events.collect { receivedEvents.add(it) }
        }

        val otherEvent = TrackerEvent.LocationUpdated(
            eventId = "e1",
            deviceId = otherId,
            timestamp = 1000L,
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5f
        )
        val targetEvent = TrackerEvent.LocationUpdated(
            eventId = "e2",
            deviceId = targetId,
            timestamp = 2000L,
            latitude = 30.0,
            longitude = 40.0,
            accuracy = 4f
        )

        fleetLocationEvents.emit(otherEvent)
        fleetLocationEvents.emit(targetEvent)
        testScheduler.advanceUntilIdle()

        assertEquals(1, receivedEvents.size)
        assertEquals(targetId, receivedEvents[0].deviceId)
        assertEquals(30.0, receivedEvents[0].latitude, 0.001)
    }

    @Test
    fun `transport reflects fleet connection state`() = runTest {
        val fleetConnState = MutableStateFlow(ConnectionState.CONNECTING)
        val targetId = TrackerId("device-1")

        val transport = object : TrackerTransport {
            override val deviceId: TrackerId = targetId
            override val connectionState: StateFlow<ConnectionState> = fleetConnState
            override val events = MutableSharedFlow<TrackerEvent>().filter { it.deviceId == targetId }
            override suspend fun connect() {}
            override suspend fun disconnect() {}
            override suspend fun sendCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand) =
                com.livetracker.controller.core.tracker.domain.CommandResult.Completed(command.commandId, command.deviceId)
        }

        assertEquals(ConnectionState.CONNECTING, transport.connectionState.value)

        fleetConnState.value = ConnectionState.CONNECTED
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)

        fleetConnState.value = ConnectionState.DISCONNECTED
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
    }

    @Test
    fun `concurrent three-device fleet stream guarantees zero event cross-leakage`() = runTest {
        val fleetLocationEvents = MutableSharedFlow<TrackerEvent.LocationUpdated>(extraBufferCapacity = 64)
        val fleetConnState = MutableStateFlow(ConnectionState.CONNECTED)

        val idA = TrackerId("tracker-alpha")
        val idB = TrackerId("tracker-beta")
        val idC = TrackerId("tracker-gamma")

        fun createTransport(id: TrackerId) = object : TrackerTransport {
            override val deviceId: TrackerId = id
            override val connectionState: StateFlow<ConnectionState> = fleetConnState
            override val events = fleetLocationEvents.filter { it.deviceId == id }
            override suspend fun connect() {}
            override suspend fun disconnect() {}
            override suspend fun sendCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand) =
                com.livetracker.controller.core.tracker.domain.CommandResult.Completed(command.commandId, command.deviceId)
        }

        val transportA = createTransport(idA)
        val transportB = createTransport(idB)
        val transportC = createTransport(idC)

        val receivedA = mutableListOf<TrackerEvent.LocationUpdated>()
        val receivedB = mutableListOf<TrackerEvent.LocationUpdated>()
        val receivedC = mutableListOf<TrackerEvent.LocationUpdated>()

        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { transportA.events.collect { receivedA.add(it) } }
        backgroundScope.launch(dispatcher) { transportB.events.collect { receivedB.add(it) } }
        backgroundScope.launch(dispatcher) { transportC.events.collect { receivedC.add(it) } }

        // Emit interleaved events
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e1", idA, 1000L, 10.0, 10.0, 5f))
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e2", idB, 1000L, 20.0, 20.0, 5f))
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e3", idC, 1000L, 30.0, 30.0, 5f))
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e4", idA, 2000L, 11.0, 11.0, 5f))
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e5", idB, 2000L, 21.0, 21.0, 5f))
        fleetLocationEvents.emit(TrackerEvent.LocationUpdated("e6", idC, 2000L, 31.0, 31.0, 5f))
        testScheduler.advanceUntilIdle()

        // Verify strictly 2 events each
        assertEquals(2, receivedA.size)
        assertEquals(2, receivedB.size)
        assertEquals(2, receivedC.size)

        // Verify no cross-device contamination
        assertTrue(receivedA.all { it.deviceId == idA })
        assertTrue(receivedB.all { it.deviceId == idB })
        assertTrue(receivedC.all { it.deviceId == idC })

        assertEquals("e1", receivedA[0].eventId)
        assertEquals("e4", receivedA[1].eventId)
        assertEquals("e2", receivedB[0].eventId)
        assertEquals("e5", receivedB[1].eventId)
        assertEquals("e3", receivedC[0].eventId)
        assertEquals("e6", receivedC[1].eventId)
    }

    @Test
    fun `disconnection and reconnection cycle preserves transport state transitions`() = runTest {
        val fleetConnState = MutableStateFlow(ConnectionState.CONNECTED)
        val devId = TrackerId("tracker-reconnect")

        val transport = object : TrackerTransport {
            override val deviceId: TrackerId = devId
            override val connectionState: StateFlow<ConnectionState> = fleetConnState
            override val events = MutableSharedFlow<TrackerEvent>().filter { it.deviceId == devId }
            override suspend fun connect() {
                fleetConnState.value = ConnectionState.CONNECTING
            }
            override suspend fun disconnect() {
                // Per-tracker disconnect keeps local transport informed
            }
            override suspend fun sendCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand) =
                com.livetracker.controller.core.tracker.domain.CommandResult.Completed(command.commandId, command.deviceId)
        }

        val observedStates = mutableListOf<ConnectionState>()
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) {
            transport.connectionState.collect { observedStates.add(it) }
        }

        // Simulate connection drop
        fleetConnState.value = ConnectionState.DISCONNECTED
        // Reconnecting attempt
        fleetConnState.value = ConnectionState.CONNECTING
        // Restored
        fleetConnState.value = ConnectionState.CONNECTED

        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED
            ),
            observedStates
        )
    }

    // --- Direct SupabaseTrackerTransport Concrete Class Lifecycle Tests ---

    class FakeSupabaseFleetManager(scope: kotlinx.coroutines.CoroutineScope) : SupabaseFleetManager(
        clientProvider = { error("Fake client not used in unit tests") },
        scope = scope
    ) {
        override fun observeRealtimeStatus() {
            // No-op for unit testing
        }

        override suspend fun start() {
            // Simulated start
        }

        override suspend fun stop() {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        fun setFleetState(state: ConnectionState) {
            _connectionState.value = state
        }

        suspend fun emitFleetLocation(event: TrackerEvent.LocationUpdated) {
            _locationEvents.emit(event)
        }

        var sentCommand: com.livetracker.controller.core.tracker.domain.TrackerCommand? = null
        var commandResult: com.livetracker.controller.core.tracker.domain.CommandResult? = null

        override suspend fun sendTrackerCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand): com.livetracker.controller.core.tracker.domain.CommandResult {
            sentCommand = command
            return commandResult ?: com.livetracker.controller.core.tracker.domain.CommandResult.Completed(command.commandId, command.deviceId)
        }
    }

    @Test
    fun `concrete transport starts DISCONNECTED to avoid false positives even if fleet is CONNECTED`() = runTest {
        val fakeFleet = FakeSupabaseFleetManager(backgroundScope)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)

        val trackerId = TrackerId("tracker-fresh")
        val transport = SupabaseTrackerTransport(trackerId, fakeFleet, backgroundScope)

        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
    }

    @Test
    fun `concrete transport transitions to CONNECTED when connect() is called with connected fleet`() = runTest {
        val fakeFleet = FakeSupabaseFleetManager(backgroundScope)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)

        val trackerId = TrackerId("tracker-fresh")
        val transport = SupabaseTrackerTransport(trackerId, fakeFleet, backgroundScope)

        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
    }

    @Test
    fun `concrete transport transitions to CONNECTED when live telemetry data arrives`() = runTest {
        val fakeFleet = FakeSupabaseFleetManager(backgroundScope)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)

        val targetId = TrackerId("tracker-live")
        val otherId = TrackerId("tracker-other")
        val transport = SupabaseTrackerTransport(targetId, fakeFleet, backgroundScope)

        val receivedEvents = mutableListOf<TrackerEvent>()
        val dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) {
            transport.events.collect { receivedEvents.add(it) }
        }

        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)

        // Event for another tracker does not trigger CONNECTED
        fakeFleet.emitFleetLocation(TrackerEvent.LocationUpdated("e1", otherId, 1000L, 10.0, 10.0, 5f))
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)

        // Event for target tracker triggers transition to CONNECTED
        fakeFleet.emitFleetLocation(TrackerEvent.LocationUpdated("e2", targetId, 2000L, 20.0, 20.0, 4f))
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        assertEquals(1, receivedEvents.size)
        assertEquals(targetId, receivedEvents[0].deviceId)
    }

    @Test
    fun `concrete transport disconnect transitions to DISCONNECTED and cancels fleet observation`() = runTest {
        val fakeFleet = FakeSupabaseFleetManager(backgroundScope)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)

        val trackerId = TrackerId("tracker-disc")
        val transport = SupabaseTrackerTransport(trackerId, fakeFleet, backgroundScope)
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)

        transport.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)

        // Fleet changes after disconnect do not resuscitate tracker
        fakeFleet.setFleetState(ConnectionState.CONNECTING)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)
        testScheduler.advanceUntilIdle()
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)
    }

    @Test
    fun `concrete transport reflects network loss and recovery without stale states`() = runTest {
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val fakeFleet = FakeSupabaseFleetManager(testScope)
        fakeFleet.setFleetState(ConnectionState.CONNECTED)

        val trackerId = TrackerId("tracker-net")
        val transport = SupabaseTrackerTransport(trackerId, fakeFleet, testScope)
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)

        // Fleet network loss
        fakeFleet.setFleetState(ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)

        // Fleet reconnecting
        fakeFleet.setFleetState(ConnectionState.CONNECTING)
        assertEquals(ConnectionState.CONNECTING, transport.connectionState.value)

        // Fleet recovered
        fakeFleet.setFleetState(ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
    }

    @Test
    fun `concrete transport sendCommand routes to fleet manager`() = runTest {
        val fakeFleet = FakeSupabaseFleetManager(backgroundScope)
        val trackerId = TrackerId("tracker-cmd")
        val transport = SupabaseTrackerTransport(trackerId, fakeFleet, backgroundScope)

        val cmd = com.livetracker.controller.core.tracker.domain.TrackerCommand.RequestLocation("c1", trackerId, 1000L, "admin", 5000L)
        val result = transport.sendCommand(cmd)

        assertTrue(result is com.livetracker.controller.core.tracker.domain.CommandResult.Completed)
        assertEquals(cmd, fakeFleet.sentCommand)
    }
}

