package com.livetracker.controller.ui.screens.camera

import androidx.lifecycle.SavedStateHandle
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DefaultAuthorizationEngine
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.camera.data.DefaultCameraRepository
import com.livetracker.controller.core.tracker.camera.data.SimulatedCameraRepository
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val trackerAlpha = TrackerId("tracker-alpha")
    private val trackerBeta = TrackerId("tracker-beta")
    private val currentTime = 100_000L

    private lateinit var deviceRepository: InMemoryTrackerDeviceRepository
    private lateinit var cameraRepository: DefaultCameraRepository
    private lateinit var authEngine: DefaultAuthorizationEngine

    private var activeSession: Session? = null

    private fun createSession(
        userId: String = "user-1",
        allowedDevices: Map<String, Set<ServicePermission>> = mapOf(
            trackerAlpha.value to setOf(ServicePermission.CAMERA),
            trackerBeta.value to setOf(ServicePermission.CAMERA)
        )
    ): Session {
        val devicePerms = allowedDevices.map { (devId, perms) ->
            DevicePermission(deviceId = devId, allowedServices = perms)
        }
        return Session(
            sessionId = "sess-1",
            user = User(
                id = userId,
                displayName = "Test User",
                role = Role.USER,
                accountStatus = AccountStatus.ACTIVE,
                devicePermissions = devicePerms
            ),
            isAuthenticated = true,
            createdAt = currentTime - 1000,
            expiresAt = currentTime + 100_000
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        deviceRepository = InMemoryTrackerDeviceRepository()
        authEngine = DefaultAuthorizationEngine()

        cameraRepository = DefaultCameraRepository(
            authEngine = authEngine,
            deviceRepository = deviceRepository,
            timeProvider = { currentTime },
            allowStandaloneFallback = true
        )

        activeSession = createSession()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(
        savedStateHandle: SavedStateHandle? = null,
        session: Session? = activeSession,
        repository: CameraRepository = cameraRepository,
        engine: DefaultAuthorizationEngine? = authEngine
    ): CameraViewModel {
        val vm = CameraViewModel(
            cameraRepository = repository,
            trackerDeviceRepository = deviceRepository,
            authEngine = engine,
            savedStateHandle = savedStateHandle,
            sessionProvider = { session },
            timeProvider = { currentTime }
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect()
        }
        return vm
    }

    @Test
    fun `initial state with no devices shows NO_DEVICE_SELECTED gating`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraGatingReason.NO_DEVICE_SELECTED, state.gatingReason)
        assertFalse(state.canStart)
        assertFalse(state.isStreaming)
        assertNull(state.selectedTrackerId)
    }

    @Test
    fun `auto-selects first available device and enables start when fully capable`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(trackerAlpha, state.selectedTrackerId)
        assertEquals("Alpha Tracker", state.selectedTrackerDisplayName)
        assertTrue(state.isCameraSupported)
        assertTrue(state.isDeviceOnline)
        assertTrue(state.isPermissionGranted)
        assertEquals(CameraGatingReason.NONE, state.gatingReason)
        assertNull(state.gatingMessage)
        assertTrue(state.canStart)
        assertFalse(state.canStop)
    }

    @Test
    fun `capability gating blocks start when tracker lacks camera`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "No Cam Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = false)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCameraSupported)
        assertFalse(state.canStart)
        assertEquals(CameraGatingReason.CAPABILITY_UNSUPPORTED, state.gatingReason)
        assertTrue(state.gatingMessage?.contains("not supported") == true)
    }

    @Test
    fun `online-state gating blocks start when tracker is disconnected`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Offline Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.DISCONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDeviceOnline)
        assertFalse(state.canStart)
        assertEquals(CameraGatingReason.DEVICE_OFFLINE, state.gatingReason)
        assertTrue(state.gatingMessage?.contains("offline") == true)
    }

    @Test
    fun `permission gating blocks start when user lacks Camera service permission`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        // Session without CAMERA permission (e.g. LOCATION only)
        val restrictedSession = createSession(
            allowedDevices = mapOf(trackerAlpha.value to setOf(ServicePermission.LOCATION))
        )
        val viewModel = createViewModel(session = restrictedSession)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isPermissionGranted)
        assertFalse(state.canStart)
        assertEquals(CameraGatingReason.PERMISSION_DENIED, state.gatingReason)
        assertTrue(state.gatingMessage?.contains("denied") == true)
    }

    @Test
    fun `startCamera transitions to STREAMING and populates requested config`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setQualityProfile(QualityProfile.HIGH)
        viewModel.setCameraFacing(CameraFacing.FRONT)
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isStreaming)
        assertEquals(StreamLifecycleState.STREAMING, state.lifecycleState)
        assertTrue(state.canStop)
        assertTrue(state.canSwitch)
        assertFalse(state.canStart)

        // Verify requested config matches chosen settings
        assertEquals(CameraResolution.FULL_HD_1080P, state.requestedConfig?.resolution)
        assertEquals(30, state.requestedConfig?.fps)
        assertEquals(CameraFacing.FRONT, state.requestedConfig?.facing)

        // Verify actualConfig is strictly null (NEVER fabricated)
        assertNull(state.actualConfig)

        // Feed verified telemetry from remote stream
        val telemetry = ActualCaptureConfig(
            resolution = CameraResolution.HD_720P,
            fps = 23.9,
            bitrateKbps = 1480,
            facing = CameraFacing.FRONT
        )
        cameraRepository.updateActualConfig(trackerAlpha, telemetry)
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertNotNull(updatedState.actualConfig)
        assertEquals(CameraResolution.HD_720P, updatedState.actualConfig?.resolution)
        assertEquals(23.9, updatedState.actualConfig?.fps ?: 0.0, 0.01)
        assertEquals(1480, updatedState.actualConfig?.bitrateKbps)
    }

    @Test
    fun `stopCamera transitions active stream to IDLE`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.stopCamera()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(StreamLifecycleState.IDLE, state.lifecycleState)
        assertTrue(state.canStart)
        assertFalse(state.canStop)
    }

    @Test
    fun `switchCamera toggles facing on active stream`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(CameraFacing.BACK, viewModel.uiState.value.selectedFacing)

        viewModel.startCamera()
        advanceUntilIdle()

        viewModel.switchCamera()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraFacing.FRONT, state.selectedFacing)
        assertEquals(CameraFacing.FRONT, state.requestedConfig?.facing)
    }

    @Test
    fun `setQualityProfile restarts stream with new profile if currently streaming`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertEquals(QualityProfile.MEDIUM, viewModel.uiState.value.selectedProfile)

        viewModel.setQualityProfile(QualityProfile.HIGH)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(QualityProfile.HIGH, state.selectedProfile)
        assertEquals(CameraResolution.FULL_HD_1080P, state.requestedConfig?.resolution)
        assertTrue(state.isStreaming)
    }

    @Test
    fun `safe tracker switching terminates previous tracker stream before observing new tracker`() = runTest(testDispatcher) {
        val dev1 = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        val dev2 = TrackerDevice(
            deviceId = trackerBeta,
            displayName = "Beta Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev1)
        deviceRepository.registerDevice(dev2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Start stream on tracker Alpha
        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        // Switch to tracker Beta
        viewModel.selectTracker(trackerBeta)
        advanceUntilIdle()

        // Verify Alpha stream was cleanly stopped in repository
        val alphaState = cameraRepository.getCameraState(trackerAlpha)
        assertEquals(StreamLifecycleState.IDLE, alphaState?.lifecycleState)

        // Verify ViewModel now observes Beta in IDLE state
        val betaState = viewModel.uiState.value
        assertEquals(trackerBeta, betaState.selectedTrackerId)
        assertEquals(StreamLifecycleState.IDLE, betaState.lifecycleState)
        assertFalse(betaState.isStreaming)
    }

    @Test
    fun `SavedStateHandle restores tracker selection and configuration across process recreation`() = runTest(testDispatcher) {
        val dev1 = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        val dev2 = TrackerDevice(
            deviceId = trackerBeta,
            displayName = "Beta Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev1)
        deviceRepository.registerDevice(dev2)

        val handle = SavedStateHandle(
            mapOf(
                CameraViewModel.KEY_SELECTED_TRACKER_ID to trackerBeta.value,
                CameraViewModel.KEY_SELECTED_PROFILE to QualityProfile.HIGH.name,
                CameraViewModel.KEY_SELECTED_FACING to CameraFacing.FRONT.name
            )
        )

        val viewModel = createViewModel(savedStateHandle = handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(trackerBeta, state.selectedTrackerId)
        assertEquals(QualityProfile.HIGH, state.selectedProfile)
        assertEquals(CameraFacing.FRONT, state.selectedFacing)
    }

    @Test
    fun `error message can be dismissed`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Attempt start with no device selected
        viewModel.startCamera()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.dismissError()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `setQualityProfile surfaces error when restarting stream fails`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val simulatedRepo = SimulatedCameraRepository(
            simulationLabel = "Test Simulator",
            supportedDevices = setOf(trackerAlpha),
            authorizedDevices = setOf(trackerAlpha)
        )

        val viewModel = createViewModel(repository = simulatedRepo)
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        // Inject failure for subsequent start command during restart
        simulatedRepo.simulatedCommandFailure = "ENCODER_RESTART_FAILED"

        viewModel.setQualityProfile(QualityProfile.HIGH)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("ENCODER_RESTART_FAILED", state.errorMessage)
        assertFalse("Operating flag must be reset on failure", state.isOperating)
        assertFalse("Stream should not be reported as streaming when restart failed", state.isStreaming)
    }

    @Test
    fun `default tracker selection is persisted to SavedStateHandle by dedicated coroutine`() = runTest(testDispatcher) {
        val handle = SavedStateHandle()
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel(savedStateHandle = handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(trackerAlpha, state.selectedTrackerId)
        assertEquals(trackerAlpha.value, handle.get<String>(CameraViewModel.KEY_SELECTED_TRACKER_ID))
    }

    @Test
    fun `tracker list updates dynamically reflect in UI state without altering explicit selection`() = runTest(testDispatcher) {
        val dev1 = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev1)

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(trackerAlpha, viewModel.uiState.value.selectedTrackerId)
        assertEquals(1, viewModel.uiState.value.availableTrackers.size)

        // Add second device
        val dev2 = TrackerDevice(
            deviceId = trackerBeta,
            displayName = "Beta Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev2)
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertEquals(trackerAlpha, updatedState.selectedTrackerId) // Explicit selection untouched
        assertEquals(2, updatedState.availableTrackers.size)
    }

    @Test
    fun `authEngine is null enforces fail-closed permission denial`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        // Pass engine = null to test fail-closed behavior
        val viewModel = createViewModel(engine = null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Permission must NOT be granted when authEngine is null", state.isPermissionGranted)
        assertEquals(CameraGatingReason.PERMISSION_DENIED, state.gatingReason)
        assertFalse("Cannot start camera when permission is not granted", state.canStart)
    }

    @Test
    fun `disposing ViewModel via onCleared stops active camera stream`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue("Stream must be active prior to disposal", viewModel.uiState.value.isStreaming)
        assertEquals(StreamLifecycleState.STREAMING, cameraRepository.getCameraState(trackerAlpha)?.lifecycleState)

        // User navigates away / backstack entry popped -> onCleared()
        viewModel.onCleared()
        advanceUntilIdle()

        val repoState = cameraRepository.getCameraState(trackerAlpha)
        assertEquals("Repository camera state must be restored to IDLE after disposal", StreamLifecycleState.IDLE, repoState?.lifecycleState)
    }

    @Test
    fun `ordinary screen recreation retains stream state and does not trigger onCleared cleanup`() = runTest(testDispatcher) {
        val handle = SavedStateHandle()
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel1 = createViewModel(savedStateHandle = handle)
        advanceUntilIdle()

        viewModel1.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel1.uiState.value.isStreaming)

        // Configuration change: new ViewModel instance created with retained SavedStateHandle without clearing vm1
        val viewModel2 = createViewModel(savedStateHandle = handle)
        advanceUntilIdle()

        // Stream remains active in repository and observed by new screen instance
        val repoState = cameraRepository.getCameraState(trackerAlpha)
        assertEquals(StreamLifecycleState.STREAMING, repoState?.lifecycleState)
        assertTrue(viewModel2.uiState.value.isStreaming)
    }

    @Test
    fun `cleanupActiveStream handles stop failure gracefully and truthfully`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val simulatedRepo = SimulatedCameraRepository(
            simulationLabel = "Test Simulator",
            supportedDevices = setOf(trackerAlpha),
            authorizedDevices = setOf(trackerAlpha)
        )

        val viewModel = createViewModel(repository = simulatedRepo)
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        // Inject simulated stop failure
        simulatedRepo.simulatedCommandFailure = "STOP_REJECTED_BUSY"

        val cleanupJob = viewModel.cleanupActiveStream()
        cleanupJob.join()
        advanceUntilIdle()

        assertEquals("STOP_REJECTED_BUSY", viewModel.uiState.value.errorMessage)

        // onCleared must also complete cleanly without throwing
        viewModel.onCleared()
        advanceUntilIdle()
    }

    @Test
    fun `duplicate cleanupActiveStream calls are idempotent and avoid redundant stop`() = runTest(testDispatcher) {
        val device = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        val job1 = viewModel.cleanupActiveStream()
        val job2 = viewModel.cleanupActiveStream()
        job1.join()
        job2.join()
        advanceUntilIdle()

        assertEquals(job1, job2)
        assertEquals(StreamLifecycleState.IDLE, cameraRepository.getCameraState(trackerAlpha)?.lifecycleState)
    }
}
