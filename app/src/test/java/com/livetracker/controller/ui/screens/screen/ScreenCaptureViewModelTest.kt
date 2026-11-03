package com.livetracker.controller.ui.screens.screen

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.device.data.DefaultDeviceSelectionManager
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureRepository
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureState
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenCaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testTrackerId = TrackerId("tracker-test-1")

    private lateinit var fakeScreenRepo: FakeScreenCaptureRepository
    private lateinit var fakeWebRtcSessionManager: FakeWebRtcSessionManager
    private lateinit var deviceSelectionManager: DefaultDeviceSelectionManager
    private lateinit var fakeSessionRepo: FakeSessionRepository
    private lateinit var deviceRepo: InMemoryTrackerDeviceRepository

    private val testSession = Session(
        sessionId = "sess-1",
        user = User(
            id = "u-1",
            displayName = "Admin",
            role = Role.ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        ),
        isAuthenticated = true,
        createdAt = 1000L,
        expiresAt = 2000L
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakeScreenRepo = FakeScreenCaptureRepository()
        fakeWebRtcSessionManager = FakeWebRtcSessionManager()
        deviceSelectionManager = DefaultDeviceSelectionManager()
        fakeSessionRepo = FakeSessionRepository(testSession)
        deviceRepo = InMemoryTrackerDeviceRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `auto selects first device when available`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = testTrackerId,
            displayName = "Samsung Galaxy",
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(screen = true),
            registrationTimestamp = 1000L
        )
        deviceRepo.registerDevice(device)

        val viewModel = ScreenCaptureViewModel(
            screenCaptureRepository = fakeScreenRepo,
            webRtcSessionManager = fakeWebRtcSessionManager,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = fakeSessionRepo,
            trackerDeviceRepository = deviceRepo
        )

        advanceUntilIdle()

        assertEquals("tracker-test-1", viewModel.uiState.value.deviceId)
        assertEquals("Samsung Galaxy", viewModel.uiState.value.displayName)
    }

    @Test
    fun `startScreenCapture dispatches successfully and updates state`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = testTrackerId,
            displayName = "Samsung Galaxy",
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(screen = true),
            registrationTimestamp = 1000L
        )
        deviceRepo.registerDevice(device)

        val viewModel = ScreenCaptureViewModel(
            screenCaptureRepository = fakeScreenRepo,
            webRtcSessionManager = fakeWebRtcSessionManager,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = fakeSessionRepo,
            trackerDeviceRepository = deviceRepo
        )
        advanceUntilIdle()

        viewModel.startScreenCapture("QUALITY")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isActionPending)
        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(fakeScreenRepo.startCalled)
        assertEquals("QUALITY", fakeScreenRepo.lastProfile)
    }

    @Test
    fun `stopScreenCapture invokes repository stop`() = runTest(testDispatcher) {
        deviceSelectionManager.selectDevice(testTrackerId)

        val viewModel = ScreenCaptureViewModel(
            screenCaptureRepository = fakeScreenRepo,
            webRtcSessionManager = fakeWebRtcSessionManager,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = fakeSessionRepo,
            trackerDeviceRepository = deviceRepo
        )
        advanceUntilIdle()

        viewModel.stopScreenCapture()
        advanceUntilIdle()

        assertTrue(fakeScreenRepo.stopCalled)
    }

    @Test
    fun `screen state updates are reflected in uiState`() = runTest(testDispatcher) {
        deviceSelectionManager.selectDevice(testTrackerId)

        val viewModel = ScreenCaptureViewModel(
            screenCaptureRepository = fakeScreenRepo,
            webRtcSessionManager = fakeWebRtcSessionManager,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = fakeSessionRepo,
            trackerDeviceRepository = deviceRepo
        )
        advanceUntilIdle()

        fakeScreenRepo.emitState(
            testTrackerId,
            ScreenCaptureState(
                deviceId = testTrackerId,
                status = ScreenCaptureStatus.STREAMING,
                qualityProfile = "BALANCED"
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(ScreenCaptureStatus.STREAMING, viewModel.uiState.value.status)
        assertEquals("BALANCED", viewModel.uiState.value.qualityProfile)
    }
}

private class FakeScreenCaptureRepository : ScreenCaptureRepository {
    private val flows = mutableMapOf<TrackerId, MutableStateFlow<ScreenCaptureState>>()
    var startCalled = false
    var stopCalled = false
    var lastProfile: String? = null

    fun emitState(deviceId: TrackerId, state: ScreenCaptureState) {
        getOrCreateFlow(deviceId).value = state
    }

    private fun getOrCreateFlow(deviceId: TrackerId): MutableStateFlow<ScreenCaptureState> {
        return flows.getOrPut(deviceId) { MutableStateFlow(ScreenCaptureState(deviceId = deviceId)) }
    }

    override fun observeScreenState(deviceId: TrackerId): Flow<ScreenCaptureState> {
        return getOrCreateFlow(deviceId).asStateFlow()
    }

    override suspend fun getScreenState(deviceId: TrackerId): ScreenCaptureState? {
        return flows[deviceId]?.value
    }

    override suspend fun startScreenCapture(
        deviceId: TrackerId,
        qualityProfile: String,
        session: Session?
    ): Result<Unit> {
        startCalled = true
        lastProfile = qualityProfile
        return Result.success(Unit)
    }

    override suspend fun stopScreenCapture(deviceId: TrackerId, session: Session?): Result<Unit> {
        stopCalled = true
        return Result.success(Unit)
    }
}

private class FakeWebRtcSessionManager : WebRtcSessionManager {
    private val sessions = mutableMapOf<TrackerId, WebRtcSession>()

    override fun getSession(trackerId: TrackerId): WebRtcSession? = sessions[trackerId]

    override fun createSession(
        trackerId: TrackerId,
        sessionId: String,
        configuration: WebRtcConfiguration
    ): WebRtcSession {
        throw UnsupportedOperationException()
    }

    override fun removeSession(trackerId: TrackerId) {
        sessions.remove(trackerId)
    }

    override fun getAllSessions(): Map<TrackerId, WebRtcSession> = sessions
}

private class FakeSessionRepository(private val session: Session) : SessionRepository {
    override suspend fun createSession(session: Session): Result<Unit> = Result.success(Unit)
    override suspend fun getActiveSession(): Result<Session> = Result.success(session)
    override suspend fun updateSessionUser(user: User): Result<Unit> = Result.success(Unit)
    override suspend fun invalidateSession(): Result<Unit> = Result.success(Unit)
    override fun observeActiveSession(): Flow<Session?> = MutableStateFlow(session)
}
