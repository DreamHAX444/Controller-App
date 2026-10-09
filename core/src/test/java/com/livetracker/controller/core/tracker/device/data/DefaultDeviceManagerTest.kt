package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.device.domain.*
import com.livetracker.controller.core.tracker.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDeviceManagerTest {

    private lateinit var repository: TrackerDeviceRepository
    private lateinit var sessionManager: FakeTrackerSessionManager
    private var currentTime = 1000L

    @Before
    fun setup() {
        repository = InMemoryTrackerDeviceRepository()
        sessionManager = FakeTrackerSessionManager()
    }

    private fun kotlinx.coroutines.test.TestScope.createDeviceManager(): DefaultDeviceManager {
        return DefaultDeviceManager(
            scope = backgroundScope,
            repository = repository,
            sessionManager = sessionManager,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun `registerDevice stores device in repository and starts session sync`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        
        val device = repository.getDevice(deviceId)
        requireNotNull(device)
        assertEquals("Tracker 1", device.displayName)
        assertEquals(DeviceLifecycle.REGISTERED, device.lifecycle)
        
        // Ensure session manager is aware
        assertTrue(sessionManager.hasSession(deviceId))
    }
    
    @Test
    fun `enableDevice changes lifecycle to ACTIVE`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        
        deviceManager.enableDevice(deviceId)
        
        val device = repository.getDevice(deviceId)
        assertEquals(DeviceLifecycle.ACTIVE, device?.lifecycle)
    }
    
    @Test
    fun `disableDevice changes lifecycle to DISABLED`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        deviceManager.enableDevice(deviceId)
        
        deviceManager.disableDevice(deviceId)
        
        val device = repository.getDevice(deviceId)
        assertEquals(DeviceLifecycle.DISABLED, device?.lifecycle)
    }
    
    @Test
    fun `connectDevice connects if ACTIVE`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        deviceManager.enableDevice(deviceId)
        
        deviceManager.connectDevice(deviceId)
        
        assertTrue(sessionManager.isConnected(deviceId))
    }
    
    @Test
    fun `connectDevice ignores if DISABLED`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        deviceManager.disableDevice(deviceId)
        
        deviceManager.connectDevice(deviceId)
        
        assertTrue(!sessionManager.isConnected(deviceId))
    }
    
    @Test
    fun `session sync updates connection state and health`() = runTest {
        val deviceManager = createDeviceManager()
        val deviceId = TrackerId("DEV1")
        deviceManager.registerDevice(deviceId, "Tracker 1")
        
        // Advance time to allow coroutine to start collecting
        advanceTimeBy(100)
        
        sessionManager.emitState(deviceId, TrackerSessionState(
            deviceId = deviceId,
            connectionState = ConnectionState.CONNECTED,
            heartbeatState = HeartbeatState(health = HeartbeatHealth.HEALTHY)
        ))
        
        advanceTimeBy(100)
        
        val device = repository.getDevice(deviceId)
        assertEquals(ConnectionState.CONNECTED, device?.connectionState)
        assertEquals(DeviceHealth.HEALTHY, device?.health)
    }
}

class FakeTrackerSessionManager : TrackerSessionManager {
    private val sessions = mutableMapOf<TrackerId, MutableStateFlow<TrackerSessionState>>()
    private val connectionStatuses = mutableMapOf<TrackerId, Boolean>()

    override fun registerTracker(deviceId: TrackerId, displayName: String) {
        sessions[deviceId] = MutableStateFlow(TrackerSessionState(deviceId = deviceId, displayName = displayName))
        connectionStatuses[deviceId] = false
    }

    override fun unregisterTracker(deviceId: TrackerId) {
        sessions.remove(deviceId)
        connectionStatuses.remove(deviceId)
    }

    override fun clearSessions() {
        sessions.clear()
        connectionStatuses.clear()
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

    override suspend fun startConnection(deviceId: TrackerId) {
        connectionStatuses[deviceId] = true
    }

    override suspend fun disconnect(deviceId: TrackerId) {
        connectionStatuses[deviceId] = false
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        return CommandResult.Accepted(command.commandId, command.deviceId)
    }
    
    fun emitState(deviceId: TrackerId, state: TrackerSessionState) {
        sessions[deviceId]?.value = state
    }
    
    fun isConnected(deviceId: TrackerId): Boolean {
        return connectionStatuses[deviceId] == true
    }
}
