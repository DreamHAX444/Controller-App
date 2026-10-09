package com.livetracker.controller.core.tracker.diagnostics.data

import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.diagnostics.domain.DiagnosticEvent
import com.livetracker.controller.core.tracker.diagnostics.domain.DiagnosticEventType
import com.livetracker.controller.core.tracker.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackerDiagnosticsProviderTest {

    private lateinit var testScope: TestScope
    private lateinit var deviceRepository: InMemoryTrackerDeviceRepository
    private lateinit var sessionManager: FakeTrackerSessionManagerForDiag
    private lateinit var historyBuffer: DiagnosticHistoryBuffer
    private lateinit var diagnosticsProvider: DefaultTrackerDiagnosticsProvider
    
    @Before
    fun setup() {
        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        deviceRepository = InMemoryTrackerDeviceRepository()
        sessionManager = FakeTrackerSessionManagerForDiag()
        historyBuffer = DiagnosticHistoryBuffer()
        
        diagnosticsProvider = DefaultTrackerDiagnosticsProvider(
            sessionManager = sessionManager,
            deviceRepository = deviceRepository,
            historyBuffer = historyBuffer
        )
    }

    @Test
    fun `getDiagnostics returns null for unknown device`() = testScope.runTest {
        val deviceId = TrackerId("UNKNOWN")
        val diag = diagnosticsProvider.getDiagnostics(deviceId)
        assertEquals(null, diag)
    }
    
    @Test
    fun `getDiagnostics returns merged state`() = testScope.runTest {
        val deviceId = TrackerId("DEV1")
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = deviceId,
                displayName = "Tracker",
                registrationTimestamp = 100L,
                connectionState = ConnectionState.CONNECTED,
                health = DeviceHealth.HEALTHY,
                lifecycle = DeviceLifecycle.ACTIVE,
                batteryLevel = 90
            )
        )
        
        sessionManager.registerTracker(deviceId, "Tracker")
        sessionManager.emitState(deviceId, TrackerSessionState(
            deviceId = deviceId,
            connectionState = ConnectionState.CONNECTED,
            signalingState = "CONNECTED",
            webrtcState = "CONNECTED",
            heartbeatState = HeartbeatState(health = HeartbeatHealth.HEALTHY)
        ))
        
        val diag = diagnosticsProvider.getDiagnostics(deviceId)
        assertNotNull(diag)
        assertEquals(ConnectionState.CONNECTED, diag?.connection?.connectionState)
        assertEquals(90, diag?.health?.battery)
    }
    
    @Test
    fun `history buffer stores and retrieves events`() = testScope.runTest {
        val deviceId = TrackerId("DEV1")
        historyBuffer.addEvent(deviceId, DiagnosticEvent(
            type = DiagnosticEventType.CONNECTED,
            timestamp = 100L
        ))
        
        val historyFlow = historyBuffer.observeHistory(deviceId)
        val history = historyFlow.first()
        assertEquals(1, history.size)
        assertEquals(DiagnosticEventType.CONNECTED, history[0].type)
    }
    
    @Test
    fun `history buffer respects max events limit`() = testScope.runTest {
        val deviceId = TrackerId("DEV1")
        val smallBuffer = DiagnosticHistoryBuffer(maxEvents = 3)
        
        for (i in 1..5) {
            smallBuffer.addEvent(deviceId, DiagnosticEvent(
                type = DiagnosticEventType.CONNECTED,
                timestamp = i.toLong()
            ))
        }
        
        val history = smallBuffer.observeHistory(deviceId).first()
        assertEquals(3, history.size)
        assertEquals(3L, history[0].timestamp)
        assertEquals(4L, history[1].timestamp)
        assertEquals(5L, history[2].timestamp)
    }
}

class FakeTrackerSessionManagerForDiag : TrackerSessionManager {
    private val sessions = mutableMapOf<TrackerId, MutableStateFlow<TrackerSessionState>>()

    override fun registerTracker(deviceId: TrackerId, displayName: String) {
        sessions[deviceId] = MutableStateFlow(TrackerSessionState(deviceId = deviceId, displayName = displayName))
    }

    override fun unregisterTracker(deviceId: TrackerId) {
        sessions.remove(deviceId)
    }

    override fun clearSessions() {
        sessions.clear()
    }

    override fun getSession(deviceId: TrackerId): StateFlow<TrackerSessionState>? {
        return sessions[deviceId]?.asStateFlow()
    }

    override fun getAllSessions(): List<StateFlow<TrackerSessionState>> {
        return sessions.values.map { it.asStateFlow() }
    }

    override fun hasSession(deviceId: TrackerId): Boolean {
        return sessions.containsKey(deviceId)
    }

    override fun listSessions(): List<TrackerId> {
        return sessions.keys.toList()
    }

    override suspend fun startConnection(deviceId: TrackerId) {}

    override suspend fun disconnect(deviceId: TrackerId) {}

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        return CommandResult.Accepted(command.commandId, command.deviceId)
    }
    
    fun emitState(deviceId: TrackerId, state: TrackerSessionState) {
        sessions[deviceId]?.value = state
    }
}
