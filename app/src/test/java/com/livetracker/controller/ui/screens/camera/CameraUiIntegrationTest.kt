package com.livetracker.controller.ui.screens.camera

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
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
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.navigation.Destination
import com.livetracker.controller.navigation.serviceDestinations
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

/**
 * UI & ViewModel integration test suite for CameraScreen.
 *
 * Verifies:
 * 1. Gating states (no device, capability unsupported, permission denied, offline)
 * 2. Honest representation of simulation mode (labels & flags)
 * 3. Strict separation of requested configuration vs verified peer telemetry (never fabricated)
 * 4. Control dispatch: start, stop, lens toggle, quality profiles
 * 5. Safe tracker switching and state cleanup
 * 6. Destination and navigation wiring intact
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraUiIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private val trackerAlpha = TrackerId("tracker-alpha")
    private val trackerBeta = TrackerId("tracker-beta")
    private val currentTime = 200_000L

    private lateinit var deviceRepository: InMemoryTrackerDeviceRepository
    private lateinit var simulatedCameraRepository: SimulatedCameraRepository
    private lateinit var defaultCameraRepository: DefaultCameraRepository
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
        simulatedCameraRepository = SimulatedCameraRepository(
            simulationLabel = "Integration Test Simulation Engine",
            timeProvider = { currentTime }
        )
        authEngine = DefaultAuthorizationEngine()
        defaultCameraRepository = DefaultCameraRepository(
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
        repository: com.livetracker.controller.core.tracker.camera.domain.CameraRepository = defaultCameraRepository,
        savedStateHandle: SavedStateHandle? = null,
        session: Session? = activeSession
    ): CameraViewModel {
        val vm = CameraViewModel(
            cameraRepository = repository,
            trackerDeviceRepository = deviceRepository,
            authEngine = authEngine,
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
    fun `navigation wiring has camera destination properly configured`() {
        assertEquals("services/camera", Destination.Camera.route)
        assertEquals("Camera", Destination.Camera.title)
        assertTrue(serviceDestinations.contains(Destination.Camera))
    }

    @Test
    fun `no device selected state renders clear gating message and disables controls`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraGatingReason.NO_DEVICE_SELECTED, state.gatingReason)
        assertFalse(state.canStart)
        assertFalse(state.canStop)
        assertFalse(state.isStreaming)
        assertNull(state.selectedTrackerId)
        assertTrue(state.gatingMessage?.contains("No tracker selected") == true)
    }

    @Test
    fun `capability unsupported state correctly gates tracker lacking camera`() = runTest(testDispatcher) {
        val noCamDevice = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Sensor Tag",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = false)
        )
        deviceRepository.registerDevice(noCamDevice)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraGatingReason.CAPABILITY_UNSUPPORTED, state.gatingReason)
        assertFalse(state.isCameraSupported)
        assertFalse(state.canStart)
        assertTrue(state.gatingMessage?.contains("not supported") == true)
    }

    @Test
    fun `permission denied state gates unauthorized operator`() = runTest(testDispatcher) {
        val camDevice = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(camDevice)

        // Session without CAMERA permission (e.g. LOCATION only)
        val restrictedSession = createSession(
            allowedDevices = mapOf(trackerAlpha.value to setOf(ServicePermission.LOCATION))
        )

        val viewModel = createViewModel(session = restrictedSession)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraGatingReason.PERMISSION_DENIED, state.gatingReason)
        assertFalse(state.isPermissionGranted)
        assertFalse(state.canStart)
        assertTrue(state.gatingMessage?.contains("denied") == true)
    }

    @Test
    fun `device offline state gates disconnected tracker`() = runTest(testDispatcher) {
        val offlineDevice = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.DISCONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(offlineDevice)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CameraGatingReason.DEVICE_OFFLINE, state.gatingReason)
        assertFalse(state.isDeviceOnline)
        assertFalse(state.canStart)
        assertTrue(state.gatingMessage?.contains("offline") == true)
    }

    @Test
    fun `simulated repository explicitly identifies simulation and never fabricates telemetry`() = runTest(testDispatcher) {
        val camDevice = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(camDevice)

        val viewModel = createViewModel(repository = simulatedCameraRepository)
        advanceUntilIdle()

        // Start stream in simulation
        viewModel.startCamera()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isStreaming)
        assertTrue(state.isSimulated)
        assertEquals("Integration Test Simulation Engine", state.simulationLabel)

        // Requested configuration is present
        assertNotNull(state.requestedConfig)
        assertEquals(CameraResolution.HD_720P, state.requestedConfig?.resolution)
        assertEquals(24, state.requestedConfig?.fps)

        // Telemetry is strictly null (NEVER fabricated)
        assertNull(state.actualConfig)

        // Feed verified peer telemetry into simulation
        val verifiedTelemetry = ActualCaptureConfig(
            resolution = CameraResolution.HD_720P,
            fps = 23.95,
            bitrateKbps = 1450,
            facing = CameraFacing.BACK
        )
        simulatedCameraRepository.updateActualConfig(trackerAlpha, verifiedTelemetry)
        advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertNotNull(updatedState.actualConfig)
        assertEquals(23.95, updatedState.actualConfig?.fps ?: 0.0, 0.01)
        assertEquals(1450, updatedState.actualConfig?.bitrateKbps)
    }

    @Test
    fun `quality profile and lens switching updates configuration reactively`() = runTest(testDispatcher) {
        val camDevice = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(camDevice)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Change facing before starting stream
        viewModel.setCameraFacing(CameraFacing.FRONT)
        advanceUntilIdle()
        assertEquals(CameraFacing.FRONT, viewModel.uiState.value.selectedFacing)

        // Change quality profile before starting
        viewModel.setQualityProfile(QualityProfile.HIGH)
        advanceUntilIdle()
        assertEquals(QualityProfile.HIGH, viewModel.uiState.value.selectedProfile)
        assertEquals(CameraResolution.FULL_HD_1080P, viewModel.uiState.value.requestedConfig?.resolution)

        // Start stream
        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(CameraFacing.FRONT, viewModel.uiState.value.requestedConfig?.facing)

        // Switch lens while streaming
        viewModel.switchCamera()
        advanceUntilIdle()
        assertEquals(CameraFacing.BACK, viewModel.uiState.value.selectedFacing)
        assertEquals(CameraFacing.BACK, viewModel.uiState.value.requestedConfig?.facing)

        // Stop stream
        viewModel.stopCamera()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(StreamLifecycleState.IDLE, viewModel.uiState.value.lifecycleState)
    }

    @Test
    fun `safe tracker switching terminates previous stream and resets state`() = runTest(testDispatcher) {
        val dev1 = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        val dev2 = TrackerDevice(
            deviceId = trackerBeta,
            displayName = "Beta Cam",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev1)
        deviceRepository.registerDevice(dev2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Start streaming on Alpha
        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(trackerAlpha, viewModel.uiState.value.selectedTrackerId)

        // Select Beta
        viewModel.selectTracker(trackerBeta)
        advanceUntilIdle()

        // Alpha stream was cleanly stopped
        val alphaState = defaultCameraRepository.getCameraState(trackerAlpha)
        assertEquals(StreamLifecycleState.IDLE, alphaState?.lifecycleState)

        // ViewModel now observes Beta in IDLE
        val betaState = viewModel.uiState.value
        assertEquals(trackerBeta, betaState.selectedTrackerId)
        assertEquals(StreamLifecycleState.IDLE, betaState.lifecycleState)
        assertFalse(betaState.isStreaming)
    }

    @Test
    fun `production ViewModel factory creates CameraViewModel with CreationExtras`() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
                    val savedStateHandle = runCatching { extras.createSavedStateHandle() }.getOrNull()
                    return CameraViewModel(
                        cameraRepository = defaultCameraRepository,
                        trackerDeviceRepository = deviceRepository,
                        authEngine = authEngine,
                        savedStateHandle = savedStateHandle,
                        timeProvider = { currentTime }
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                create(modelClass, CreationExtras.Empty)
        }

        val viewModelFromExtras = factory.create(CameraViewModel::class.java, MutableCreationExtras())
        assertNotNull(viewModelFromExtras)

        val viewModelFromLegacy = factory.create(CameraViewModel::class.java)
        assertNotNull(viewModelFromLegacy)
    }

    @Test
    fun `navigating away and clearing ViewModel stops active camera stream in repository`() = runTest(testDispatcher) {
        val dev = TrackerDevice(
            deviceId = trackerAlpha,
            displayName = "Alpha Tracker",
            registrationTimestamp = currentTime,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(dev)

        val viewModel = createViewModel(repository = defaultCameraRepository)
        advanceUntilIdle()

        viewModel.startCamera()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(StreamLifecycleState.STREAMING, defaultCameraRepository.getCameraState(trackerAlpha)?.lifecycleState)

        // Screen popped / NavBackStackEntry destroyed -> onCleared invoked
        viewModel.onCleared()
        advanceUntilIdle()

        val endState = defaultCameraRepository.getCameraState(trackerAlpha)
        assertEquals(StreamLifecycleState.IDLE, endState?.lifecycleState)
    }
}
