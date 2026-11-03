package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.domain.TrackerSessionState
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.location.data.LocationData
import com.livetracker.controller.core.tracker.location.domain.ProcessLocationUpdateUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class FleetLifecycleWatchdogTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createSession(): Session {
        val now = System.currentTimeMillis()
        val user = User(
            id = "test-user-id",
            displayName = "Admin",
            role = Role.ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = true,
            createdAt = now,
            expiresAt = now + 3600_000L
        )
    }

    private class FakeDeviceManager(private val repository: TrackerDeviceRepository) : DeviceManager {
        override suspend fun registerDevice(deviceId: TrackerId, displayName: String) {
            if (repository.getDevice(deviceId) == null) {
                repository.registerDevice(
                    TrackerDevice(
                        deviceId = deviceId,
                        displayName = displayName,
                        registrationTimestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        override suspend fun unregisterDevice(deviceId: TrackerId) {
            repository.unregisterDevice(deviceId)
        }
        override suspend fun enableDevice(deviceId: TrackerId) {}
        override suspend fun disableDevice(deviceId: TrackerId) {}
        override suspend fun connectDevice(deviceId: TrackerId) {
            repository.updateDevice(deviceId) {
                it.copy(connectionState = ConnectionState.CONNECTED)
            }
        }
        override suspend fun disconnectDevice(deviceId: TrackerId) {
            repository.updateDevice(deviceId) {
                it.copy(connectionState = ConnectionState.DISCONNECTED)
            }
        }
    }

    private class FakeTrackerSessionManager : TrackerSessionManager {
        override fun registerTracker(deviceId: TrackerId, displayName: String) {}
        override fun unregisterTracker(deviceId: TrackerId) {}
        override fun clearSessions() {}
        override fun getSession(deviceId: TrackerId): StateFlow<TrackerSessionState>? = null
        override fun getAllSessions(): List<StateFlow<TrackerSessionState>> = emptyList()
        override fun hasSession(deviceId: TrackerId): Boolean = false
        override fun listSessions(): List<TrackerId> = emptyList()
        override suspend fun startConnection(deviceId: TrackerId) {}
        override suspend fun disconnect(deviceId: TrackerId) {}
        override suspend fun sendCommand(command: TrackerCommand): CommandResult =
            CommandResult.Completed(command.commandId, command.deviceId)
    }

    private class WatchdogTestFleetManager(scope: kotlinx.coroutines.CoroutineScope) : SupabaseFleetManager(
        clientProvider = { throw AssertionError("No direct client calls expected") },
        scope = scope
    ) {
        val fetchLocationsCallCount = AtomicInteger(0)

        fun emitTestLocationEvent(event: TrackerEvent.LocationUpdated) {
            _locationEvents.tryEmit(event)
        }

        fun emitTestTelemetry(data: LocationData) {
            _rawTelemetry.tryEmit(data)
        }

        override suspend fun startFleet(session: Session, currentTime: Long): Boolean {
            currentSession = session
            return true
        }

        override suspend fun stopFleet() {
            currentSession = null
        }

        override suspend fun registerDeviceInCloud(deviceRecord: DeviceRecord): Result<Unit> {
            return Result.success(Unit)
        }

        override suspend fun fetchFleetDevices(): List<DeviceRecord> {
            return listOf(
                DeviceRecord(deviceId = "dev-wd-1", name = "Watchdog Dev 1", status = "ACTIVE")
            )
        }

        override suspend fun fetchLatestLocations(): List<LocationData> {
            fetchLocationsCallCount.incrementAndGet()
            return listOf(
                LocationData(
                    deviceId = "dev-wd-1",
                    latitude = 37.7749,
                    longitude = -122.4194,
                    accuracy = 5.0f,
                    createdAt = "2026-10-30T12:00:00.000Z"
                )
            )
        }
    }

    @Test
    fun `eliminates 5s background polling and performs 0 network calls during periodic watchdog ticks`() = testScope.runTest {
        val fleetManager = WatchdogTestFleetManager(testScope)
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val sessionManager = FakeTrackerSessionManager()
        val deviceManager = FakeDeviceManager(deviceRepo)
        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)

        val lifecycleManager = FleetLifecycleManager(
            supabaseFleetManager = fleetManager,
            deviceManager = deviceManager,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo,
            trackerSessionManager = sessionManager,
            processLocationUpdateUseCase = processLocationUpdateUseCase,
            scope = testScope,
            dispatcher = testDispatcher
        )

        try {
            val session = createSession()
            lifecycleManager.onSessionChanged(session)
            testScheduler.runCurrent()

            // Single cold start initial hydration occurred
            assertEquals(1, fleetManager.fetchLocationsCallCount.get())

            // Advance 30 seconds (previously this would trigger 6 HTTP polls at 5s each)
            advanceTimeBy(30_000L)
            testScheduler.runCurrent()

            // Must still be exactly 1: ZERO periodic network polling calls!
            assertEquals("fetchLatestLocations must NOT be called in background loop", 1, fleetManager.fetchLocationsCallCount.get())
        } finally {
            lifecycleManager.onSessionChanged(null)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `in-memory presence watchdog marks inactive devices as DISCONNECTED locally without network`() = testScope.runTest {
        val fleetManager = WatchdogTestFleetManager(testScope)
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val sessionManager = FakeTrackerSessionManager()
        val deviceManager = FakeDeviceManager(deviceRepo)
        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)

        val lifecycleManager = FleetLifecycleManager(
            supabaseFleetManager = fleetManager,
            deviceManager = deviceManager,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo,
            trackerSessionManager = sessionManager,
            processLocationUpdateUseCase = processLocationUpdateUseCase,
            scope = testScope,
            dispatcher = testDispatcher
        )

        try {
            lifecycleManager.onSessionChanged(createSession())
            testScheduler.runCurrent()

            val trackerId = TrackerId("dev-wd-1")
            // Set device to CONNECTED with lastSeen in the past (> 60s ago)
            val now = System.currentTimeMillis()
            deviceRepo.updateDevice(trackerId) {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    lastSeenTimestamp = now - 75_000L // 75 seconds ago
                )
            }

            // Advance 12 seconds to let the 10s watchdog tick
            advanceTimeBy(12_000L)
            testScheduler.runCurrent()

            val dev = deviceRepo.getDevice(trackerId)
            assertNotNull(dev)
            assertEquals(ConnectionState.DISCONNECTED, dev!!.connectionState)
        } finally {
            lifecycleManager.onSessionChanged(null)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `realtime WebSocket packet dynamically promotes device to CONNECTED and updates lastSeenTimestamp`() = testScope.runTest {
        val fleetManager = WatchdogTestFleetManager(testScope)
        val deviceRepo = InMemoryTrackerDeviceRepository()
        val locationRepo = InMemoryTrackerLocationRepository()
        val sessionManager = FakeTrackerSessionManager()
        val deviceManager = FakeDeviceManager(deviceRepo)
        val processLocationUpdateUseCase = ProcessLocationUpdateUseCase(locationRepo)

        val lifecycleManager = FleetLifecycleManager(
            supabaseFleetManager = fleetManager,
            deviceManager = deviceManager,
            trackerDeviceRepository = deviceRepo,
            trackerLocationRepository = locationRepo,
            trackerSessionManager = sessionManager,
            processLocationUpdateUseCase = processLocationUpdateUseCase,
            scope = testScope,
            dispatcher = testDispatcher
        )

        try {
            lifecycleManager.onSessionChanged(createSession())
            testScheduler.runCurrent()

            val trackerId = TrackerId("dev-wd-1")
            // Start device as DISCONNECTED
            deviceRepo.updateDevice(trackerId) {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    lastSeenTimestamp = 1000L
                )
            }

            val eventTime = System.currentTimeMillis() + 5000L
            val incomingEvent = TrackerEvent.LocationUpdated(
                eventId = "evt-wd-1",
                deviceId = trackerId,
                latitude = 37.7750,
                longitude = -122.4195,
                accuracy = 4.0f,
                timestamp = eventTime
            )
            fleetManager.emitTestLocationEvent(incomingEvent)
            testScheduler.runCurrent()

            val dev = deviceRepo.getDevice(trackerId)
            assertNotNull(dev)
            assertEquals(ConnectionState.CONNECTED, dev!!.connectionState)
            assertEquals(eventTime, dev.lastSeenTimestamp)
        } finally {
            lifecycleManager.onSessionChanged(null)
            testScheduler.runCurrent()
        }
    }
}
