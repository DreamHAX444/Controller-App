package com.livetracker.controller.core.tracker.screen

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DefaultAuthorizationEngine
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.domain.TrackerSessionState
import com.livetracker.controller.core.tracker.screen.data.DefaultScreenCaptureRepository
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultScreenCaptureRepositoryTest {

    private val deviceId = TrackerId("tracker-1")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private var currentTime = 1000L

    private lateinit var authEngine: DefaultAuthorizationEngine
    private lateinit var fakeSessionManager: FakeTrackerSessionManager
    private lateinit var repository: DefaultScreenCaptureRepository

    private class FakeTrackerSessionManager : TrackerSessionManager {
        val sentCommands = mutableListOf<TrackerCommand>()

        override fun registerTracker(deviceId: TrackerId, displayName: String) {}
        override fun unregisterTracker(deviceId: TrackerId) {}
        override fun clearSessions() {}

        override fun getSession(deviceId: TrackerId): StateFlow<TrackerSessionState>? = null
        override fun getAllSessions(): List<StateFlow<TrackerSessionState>> = emptyList()
        override fun hasSession(deviceId: TrackerId): Boolean = true
        override fun listSessions(): List<TrackerId> = emptyList()

        override suspend fun startConnection(deviceId: TrackerId) {}
        override suspend fun disconnect(deviceId: TrackerId) {}

        override suspend fun sendCommand(command: TrackerCommand): CommandResult {
            sentCommands.add(command)
            return CommandResult.Completed(command.commandId, command.deviceId)
        }
    }

    private fun createValidSession(
        userId: String = "admin-1",
        role: Role = Role.ADMIN,
        permissions: Set<ServicePermission> = setOf(ServicePermission.SCREEN_CAPTURE)
    ): Session {
        return Session(
            sessionId = "session-1",
            user = User(
                id = userId,
                displayName = "Admin User",
                role = role,
                accountStatus = AccountStatus.ACTIVE,
                devicePermissions = listOf(
                    DevicePermission(
                        deviceId = deviceId.value,
                        allowedServices = permissions
                    )
                )
            ),
            isAuthenticated = true,
            expiresAt = currentTime + 3600_000L,
            createdAt = currentTime
        )
    }

    @Before
    fun setup() {
        authEngine = DefaultAuthorizationEngine()
        fakeSessionManager = FakeTrackerSessionManager()
        repository = DefaultScreenCaptureRepository(
            sessionManager = fakeSessionManager,
            authEngine = authEngine,
            scope = testScope,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun observeScreenState_initial_returnsIdle() = runTest(testDispatcher) {
        val state = repository.observeScreenState(deviceId).first()
        assertEquals(ScreenCaptureStatus.IDLE, state.status)
        assertEquals(deviceId, state.deviceId)
    }

    @Test
    fun startScreenCapture_authorized_dispatchesCommandAndEntersStreaming() = runTest(testDispatcher) {
        val session = createValidSession()
        val result = repository.startScreenCapture(deviceId, "BALANCED", session)

        assertTrue(result.isSuccess)
        val state = repository.getScreenState(deviceId)
        assertNotNull(state)
        assertEquals(ScreenCaptureStatus.STREAMING, state?.status)
        assertEquals("BALANCED", state?.qualityProfile)

        assertEquals(1, fakeSessionManager.sentCommands.size)
        val cmd = fakeSessionManager.sentCommands.first()
        assertTrue(cmd is TrackerCommand.StartScreen)
        assertEquals(deviceId, (cmd as TrackerCommand.StartScreen).deviceId)
        assertEquals("BALANCED", cmd.qualityProfile)
    }

    @Test
    fun startScreenCapture_unauthorized_failsClosed() = runTest(testDispatcher) {
        // User lacks SCREEN_CAPTURE permission
        val session = createValidSession(role = Role.USER, permissions = setOf(ServicePermission.LOCATION))
        val result = repository.startScreenCapture(deviceId, "HIGH", session)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)

        val state = repository.getScreenState(deviceId)
        assertEquals(ScreenCaptureStatus.ERROR, state?.status)
        assertTrue(fakeSessionManager.sentCommands.isEmpty())
    }

    @Test
    fun stopScreenCapture_dispatchesStopCommandAndEntersStopped() = runTest(testDispatcher) {
        val session = createValidSession()
        repository.startScreenCapture(deviceId, "LOW", session)

        val stopResult = repository.stopScreenCapture(deviceId, session)
        assertTrue(stopResult.isSuccess)

        val state = repository.getScreenState(deviceId)
        assertEquals(ScreenCaptureStatus.STOPPED, state?.status)

        assertEquals(2, fakeSessionManager.sentCommands.size)
        val lastCmd = fakeSessionManager.sentCommands.last()
        assertTrue(lastCmd is TrackerCommand.StopScreen)
        assertEquals(deviceId, (lastCmd as TrackerCommand.StopScreen).deviceId)
    }
}
