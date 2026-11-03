package com.livetracker.controller.core.tracker.camera

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DefaultAuthorizationEngine
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.camera.data.DefaultCameraRepository
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraError
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.domain.TrackerSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCameraRepositoryTest {

    private val deviceId1 = TrackerId("tracker-alpha")
    private val deviceId2 = TrackerId("tracker-beta")
    private var currentTime = 10_000L

    private lateinit var authEngine: DefaultAuthorizationEngine
    private lateinit var deviceRepository: InMemoryTrackerDeviceRepository
    private lateinit var fakeSessionManager: FakeTrackerSessionManager
    private lateinit var repository: DefaultCameraRepository

    private fun createValidSession(
        userId: String = "user-1",
        deviceId: String = deviceId1.value,
        permissions: Set<ServicePermission> = setOf(ServicePermission.CAMERA)
    ): Session {
        val user = User(
            id = userId,
            displayName = "Operator",
            role = Role.USER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = listOf(
                DevicePermission(
                    deviceId = deviceId,
                    allowedServices = permissions
                )
            )
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = true,
            createdAt = currentTime - 1000,
            expiresAt = currentTime + 100_000
        )
    }

    @Before
    fun setUp() = runTest {
        authEngine = DefaultAuthorizationEngine()
        deviceRepository = InMemoryTrackerDeviceRepository()
        fakeSessionManager = FakeTrackerSessionManager()

        // Register default capable device
        val device = TrackerDevice(
            deviceId = deviceId1,
            displayName = "Tracker Alpha",
            registrationTimestamp = currentTime,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device)

        fakeSessionManager.setCapabilities(deviceId1, TrackerCapabilities(camera = true))

        repository = DefaultCameraRepository(
            sessionManager = fakeSessionManager,
            authEngine = authEngine,
            deviceRepository = deviceRepository,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun `initial state emits IDLE for unstarted tracker`() = runTest {
        val state = repository.observeCameraState(deviceId1).first()

        assertEquals(deviceId1, state.deviceId)
        assertEquals(StreamLifecycleState.IDLE, state.lifecycleState)
        assertNull(state.requestedConfig)
        assertNull(state.actualConfig)
        assertNull(state.lastError)
        assertFalse(state.isSimulated)
    }

    @Test
    fun `startCamera transitions to STREAMING on accepted command`() = runTest {
        val session = createValidSession()
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)

        val result = repository.startCamera(deviceId1, config, session)

        assertTrue(result is CameraResult.Success)
        val success = result as CameraResult.Success
        assertEquals(StreamLifecycleState.STREAMING, success.state.lifecycleState)
        assertEquals(config, success.state.requestedConfig)
        assertNull(success.state.actualConfig) // Never fabricated
        assertNull(success.state.lastError)

        val observed = repository.getCameraState(deviceId1)
        assertNotNull(observed)
        assertEquals(StreamLifecycleState.STREAMING, observed?.lifecycleState)
    }

    @Test
    fun `stopCamera transitions to IDLE on accepted command`() = runTest {
        val session = createValidSession()
        repository.startCamera(deviceId1, session = session)

        val stopResult = repository.stopCamera(deviceId1, session)

        assertTrue(stopResult is CameraResult.Success)
        val success = stopResult as CameraResult.Success
        assertEquals(StreamLifecycleState.IDLE, success.state.lifecycleState)
        assertNull(success.state.requestedConfig)
        assertNull(success.state.actualConfig)
        assertNull(success.state.lastError)
    }

    @Test
    fun `switchCamera updates facing on active stream`() = runTest {
        val session = createValidSession()
        repository.startCamera(deviceId1, QualityProfile.MEDIUM.toCaptureConfig(facing = CameraFacing.BACK), session)

        val switchResult = repository.switchCamera(deviceId1, CameraFacing.FRONT, session)

        assertTrue(switchResult is CameraResult.Success)
        val success = switchResult as CameraResult.Success
        assertEquals(StreamLifecycleState.STREAMING, success.state.lifecycleState)
        assertEquals(CameraFacing.FRONT, success.state.requestedConfig?.facing)
    }

    @Test
    fun `invalid state transitions return InvalidStateTransition without mutating state`() = runTest {
        val session = createValidSession()

        // 1. Cannot stop when IDLE
        val stopWhenIdle = repository.stopCamera(deviceId1, session)
        assertTrue(stopWhenIdle is CameraResult.InvalidStateTransition)
        val stopFail = stopWhenIdle as CameraResult.InvalidStateTransition
        assertEquals(StreamLifecycleState.IDLE, stopFail.currentState)
        assertEquals("STOP", stopFail.attemptedAction)

        // 2. Cannot switch when IDLE
        val switchWhenIdle = repository.switchCamera(deviceId1, CameraFacing.FRONT, session)
        assertTrue(switchWhenIdle is CameraResult.InvalidStateTransition)

        // 3. Start streaming
        repository.startCamera(deviceId1, session = session)

        // 4. Cannot start when already STREAMING
        val startWhenStreaming = repository.startCamera(deviceId1, session = session)
        assertTrue(startWhenStreaming is CameraResult.InvalidStateTransition)
        val startFail = startWhenStreaming as CameraResult.InvalidStateTransition
        assertEquals(StreamLifecycleState.STREAMING, startFail.currentState)
        assertEquals("START", startFail.attemptedAction)
    }

    @Test
    fun `startCamera returns UnsupportedDevice when device lacks camera capability`() = runTest {
        val incapableDevice = TrackerDevice(
            deviceId = deviceId2,
            displayName = "Tracker Beta",
            registrationTimestamp = currentTime,
            capabilities = TrackerCapabilities(camera = false)
        )
        deviceRepository.registerDevice(incapableDevice)

        val session = createValidSession(deviceId = deviceId2.value)
        val result = repository.startCamera(deviceId2, session = session)

        assertTrue(result is CameraResult.UnsupportedDevice)
        val unsupported = result as CameraResult.UnsupportedDevice
        assertEquals(deviceId2, unsupported.deviceId)

        val state = repository.getCameraState(deviceId2)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.UnsupportedCapability)
    }

    @Test
    fun `startCamera returns PermissionDenied when user lacks Camera service permission`() = runTest {
        // User only has LOCATION permission
        val session = createValidSession(
            permissions = setOf(ServicePermission.LOCATION)
        )

        val result = repository.startCamera(deviceId1, session = session)

        assertTrue(result is CameraResult.PermissionDenied)
        val denied = result as CameraResult.PermissionDenied
        assertEquals(deviceId1, denied.deviceId)

        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.PermissionDenied)
    }

    @Test
    fun `startCamera returns PermissionDenied when session is unauthenticated or expired`() = runTest {
        val expiredSession = Session(
            sessionId = "sess-expired",
            user = User("u1", "Op", Role.USER, AccountStatus.ACTIVE, emptyList()),
            isAuthenticated = true,
            createdAt = currentTime - 10_000,
            expiresAt = currentTime - 100 // Expired
        )

        val result = repository.startCamera(deviceId1, session = expiredSession)
        assertTrue(result is CameraResult.PermissionDenied)
    }

    @Test
    fun `startCamera handles rejected command and records failure`() = runTest {
        fakeSessionManager.nextCommandResult = CommandResult.Rejected("cmd-1", deviceId1, "HARDWARE_LOCKED")
        val session = createValidSession()

        val result = repository.startCamera(deviceId1, session = session)

        assertTrue(result is CameraResult.CommandFailed)
        val failure = result as CameraResult.CommandFailed
        assertTrue(failure.reason.contains("HARDWARE_LOCKED"))

        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.CommandFailure)
    }

    @Test
    fun `startCamera handles command timeout and records failure`() = runTest {
        fakeSessionManager.nextCommandResult = CommandResult.Timeout("cmd-1", deviceId1)
        val session = createValidSession()

        val result = repository.startCamera(deviceId1, session = session)

        assertTrue(result is CameraResult.CommandFailed)
        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.Timeout)
    }

    @Test
    fun `requested versus actual capture configuration is tracked distinctly without fabrication`() = runTest {
        val session = createValidSession()
        val requested = CameraCaptureConfig(
            resolution = CameraResolution.FULL_HD_1080P,
            fps = 30,
            bitrateKbps = 3000,
            facing = CameraFacing.BACK
        )

        repository.startCamera(deviceId1, requested, session)

        // Verify initial state has requested config and null actualConfig
        var state = repository.getCameraState(deviceId1)
        assertNotNull(state?.requestedConfig)
        assertEquals(CameraResolution.FULL_HD_1080P, state?.requestedConfig?.resolution)
        assertEquals(30, state?.requestedConfig?.fps)
        assertNull(state?.actualConfig) // Never fabricated

        // Remote peer stream sends verified telemetry
        val telemetry = ActualCaptureConfig(
            resolution = CameraResolution.HD_720P, // e.g. tracker adjusted down due to bandwidth
            fps = 23.4,
            bitrateKbps = 1420,
            facing = CameraFacing.BACK,
            cameraId = "0"
        )
        repository.updateActualConfig(deviceId1, telemetry)

        state = repository.getCameraState(deviceId1)
        assertEquals(CameraResolution.FULL_HD_1080P, state?.requestedConfig?.resolution)
        assertEquals(30, state?.requestedConfig?.fps)
        assertEquals(CameraResolution.HD_720P, state?.actualConfig?.resolution)
        assertEquals(23.4, state?.actualConfig?.fps ?: 0.0, 0.001)
        assertEquals(1420, state?.actualConfig?.bitrateKbps)
    }

    @Test
    fun `reactive state observation and device isolation`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val session1 = createValidSession(deviceId = deviceId1.value)
        val session2 = createValidSession(deviceId = deviceId2.value)

        val device2 = TrackerDevice(
            deviceId = deviceId2,
            displayName = "Tracker Beta",
            registrationTimestamp = currentTime,
            capabilities = TrackerCapabilities(camera = true)
        )
        deviceRepository.registerDevice(device2)
        fakeSessionManager.setCapabilities(deviceId2, TrackerCapabilities(camera = true))

        val observed1 = mutableListOf<TrackerCameraState>()
        val observed2 = mutableListOf<TrackerCameraState>()

        val job1 = backgroundScope.launch(testDispatcher) {
            repository.observeCameraState(deviceId1).collect { observed1.add(it) }
        }
        val job2 = backgroundScope.launch(testDispatcher) {
            repository.observeCameraState(deviceId2).collect { observed2.add(it) }
        }

        // Both emit initial IDLE
        assertEquals(1, observed1.size)
        assertEquals(StreamLifecycleState.IDLE, observed1.last().lifecycleState)
        assertEquals(1, observed2.size)
        assertEquals(StreamLifecycleState.IDLE, observed2.last().lifecycleState)

        // Start device 1 only
        repository.startCamera(deviceId1, session = session1)

        assertEquals(StreamLifecycleState.STREAMING, observed1.last().lifecycleState)
        // Device 2 remains completely untouched
        assertEquals(1, observed2.size)
        assertEquals(StreamLifecycleState.IDLE, observed2.last().lifecycleState)

        // Start device 2
        repository.startCamera(deviceId2, session = session2)
        assertEquals(StreamLifecycleState.STREAMING, observed2.last().lifecycleState)

        // Cancel job1 and stop device 1
        job1.cancel()
        repository.stopCamera(deviceId1, session = session1)
        job2.cancel()
    }

    @Test
    fun `clearCameraState resets stored state`() = runTest {
        val session = createValidSession()
        repository.startCamera(deviceId1, session = session)
        assertNotNull(repository.getCameraState(deviceId1))

        repository.clearCameraState(deviceId1)
        assertNull(repository.getCameraState(deviceId1))
    }

    @Test
    fun `startCamera cancellation restores previous state and rethrows CancellationException`() = runTest {
        val session = createValidSession()
        fakeSessionManager.onSendCommand = {
            throw CancellationException("Simulated cancellation during startCamera")
        }

        var exceptionThrown = false
        try {
            repository.startCamera(deviceId1, session = session)
        } catch (e: CancellationException) {
            exceptionThrown = true
        }
        assertTrue("CancellationException must be rethrown and never swallowed", exceptionThrown)

        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.IDLE, state?.lifecycleState)
        assertTrue(state?.lifecycleState?.canStart == true)

        // Verify device can now be started successfully
        fakeSessionManager.onSendCommand = null
        val retryResult = repository.startCamera(deviceId1, session = session)
        assertTrue(retryResult is CameraResult.Success)
        assertEquals(StreamLifecycleState.STREAMING, (retryResult as CameraResult.Success).state.lifecycleState)
    }

    @Test
    fun `stopCamera cancellation restores previous state and rethrows CancellationException`() = runTest {
        val session = createValidSession()
        repository.startCamera(deviceId1, session = session)
        assertEquals(StreamLifecycleState.STREAMING, repository.getCameraState(deviceId1)?.lifecycleState)

        fakeSessionManager.onSendCommand = {
            throw CancellationException("Simulated cancellation during stopCamera")
        }

        var exceptionThrown = false
        try {
            repository.stopCamera(deviceId1, session)
        } catch (e: CancellationException) {
            exceptionThrown = true
        }
        assertTrue("CancellationException must be rethrown and never swallowed", exceptionThrown)

        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.STREAMING, state?.lifecycleState)
        assertTrue(state?.lifecycleState?.canStop == true)

        // Verify device can now be stopped successfully
        fakeSessionManager.onSendCommand = null
        val retryStop = repository.stopCamera(deviceId1, session)
        assertTrue(retryStop is CameraResult.Success)
        assertEquals(StreamLifecycleState.IDLE, (retryStop as CameraResult.Success).state.lifecycleState)
    }

    @Test
    fun `switchCamera cancellation restores previous state and rethrows CancellationException`() = runTest {
        val session = createValidSession()
        val originalConfig = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK, cameraId = "cam_0")
        repository.startCamera(deviceId1, originalConfig, session)

        fakeSessionManager.onSendCommand = {
            throw CancellationException("Simulated cancellation during switchCamera")
        }

        var exceptionThrown = false
        try {
            repository.switchCamera(deviceId1, CameraFacing.FRONT, session, cameraId = "cam_1")
        } catch (e: CancellationException) {
            exceptionThrown = true
        }
        assertTrue("CancellationException must be rethrown and never swallowed", exceptionThrown)

        val state = repository.getCameraState(deviceId1)
        assertEquals(StreamLifecycleState.STREAMING, state?.lifecycleState)
        assertEquals(CameraFacing.BACK, state?.requestedConfig?.facing)
        assertEquals("cam_0", state?.requestedConfig?.cameraId)
        assertTrue(state?.lifecycleState?.canSwitch == true)
    }

    @Test
    fun `switchCamera dispatches StartCamera command with correct target camera ID`() = runTest {
        val session = createValidSession()
        val initialConfig = QualityProfile.MEDIUM.toCaptureConfig(facing = CameraFacing.BACK, cameraId = "sensor_back_0")
        repository.startCamera(deviceId1, initialConfig, session)

        // Switch to front lens with explicit cameraId
        val switchResult = repository.switchCamera(deviceId1, CameraFacing.FRONT, session, cameraId = "sensor_front_1")
        assertTrue(switchResult is CameraResult.Success)

        val lastCommand = fakeSessionManager.sentCommands.last()
        assertTrue(lastCommand is TrackerCommand.SwitchCamera)
        val switchCommand = lastCommand as TrackerCommand.SwitchCamera
        assertEquals("sensor_front_1", switchCommand.cameraId)

        val state = repository.getCameraState(deviceId1)
        assertEquals(CameraFacing.FRONT, state?.requestedConfig?.facing)
        assertEquals("sensor_front_1", state?.requestedConfig?.cameraId)
    }

    @Test
    fun `switchCamera preserves existing target camera ID when not explicitly overridden`() = runTest {
        val session = createValidSession()
        val initialConfig = QualityProfile.MEDIUM.toCaptureConfig(facing = CameraFacing.BACK, cameraId = "sensor_back_0")
        repository.startCamera(deviceId1, initialConfig, session)

        // Switch to front without overriding cameraId
        val switchResult = repository.switchCamera(deviceId1, CameraFacing.FRONT, session)
        assertTrue(switchResult is CameraResult.Success)

        val lastCommand = fakeSessionManager.sentCommands.last()
        assertTrue(lastCommand is TrackerCommand.SwitchCamera)
        val switchCommand = lastCommand as TrackerCommand.SwitchCamera
        assertEquals("sensor_back_0", switchCommand.cameraId)
    }

    @Test
    fun `startCamera fails closed when sessionManager is not configured`() = runTest {
        val unconfiguredRepo = DefaultCameraRepository(
            sessionManager = null,
            authEngine = authEngine,
            deviceRepository = deviceRepository,
            timeProvider = { currentTime }
        )
        val session = createValidSession()

        // startCamera must fail closed
        val startResult = unconfiguredRepo.startCamera(deviceId1, session = session)
        assertTrue(startResult is CameraResult.CommandFailed)
        assertTrue((startResult as CameraResult.CommandFailed).reason.contains("TrackerSessionManager not configured"))
    }

    @Test
    fun `startCamera timeout retains commandId for subsequent retry with identical identity`() = runTest {
        val session = createValidSession()
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)

        // Simulate timeout on first dispatch
        fakeSessionManager.nextCommandResult = CommandResult.Timeout("initial-cmd", deviceId1)
        val firstResult = repository.startCamera(deviceId1, config, session)
        assertTrue(firstResult is CameraResult.CommandFailed)
        assertEquals(StreamLifecycleState.ERROR, repository.getCameraState(deviceId1)?.lifecycleState)
        assertEquals(1, fakeSessionManager.sentCommands.size)
        val firstCommandId = fakeSessionManager.sentCommands[0].commandId

        // Retry the exact same start operation
        fakeSessionManager.nextCommandResult = CommandResult.Accepted(firstCommandId, deviceId1)
        val retryResult = repository.startCamera(deviceId1, config, session)
        assertTrue(retryResult is CameraResult.Success)
        assertEquals(StreamLifecycleState.STREAMING, repository.getCameraState(deviceId1)?.lifecycleState)
        assertEquals(2, fakeSessionManager.sentCommands.size)
        val retryCommandId = fakeSessionManager.sentCommands[1].commandId

        // Command identity MUST be strictly identical across retry
        assertEquals(firstCommandId, retryCommandId)
    }

    @Test
    fun `startCamera concurrent invocations coalesce into single command dispatch`() = runTest {
        val session = createValidSession()
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)

        val dispatchStarted = CompletableDeferred<Unit>()
        val dispatchCanComplete = CompletableDeferred<Unit>()

        fakeSessionManager.onSendCommand = {
            dispatchStarted.complete(Unit)
            dispatchCanComplete.await()
        }

        var result1: CameraResult? = null
        var result2: CameraResult? = null

        val job1 = launch {
            result1 = repository.startCamera(deviceId1, config, session)
        }
        dispatchStarted.await()

        val job2 = launch {
            result2 = repository.startCamera(deviceId1, config, session)
        }

        dispatchCanComplete.complete(Unit)
        job1.join()
        job2.join()

        // Only ONE command dispatch should have occurred
        assertEquals(1, fakeSessionManager.sentCommands.size)
        assertTrue(result1 is CameraResult.Success)
        assertTrue(result2 is CameraResult.Success)
        assertEquals(StreamLifecycleState.STREAMING, (result1 as CameraResult.Success).state.lifecycleState)
        assertEquals(StreamLifecycleState.STREAMING, (result2 as CameraResult.Success).state.lifecycleState)
    }

    @Test
    fun `startCamera successful completion clears pending operation so subsequent start generates fresh commandId`() = runTest {
        val session = createValidSession()
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)

        // First session: start -> success -> stop -> success
        val startResult1 = repository.startCamera(deviceId1, config, session)
        assertTrue(startResult1 is CameraResult.Success)
        val stopResult = repository.stopCamera(deviceId1, session)
        assertTrue(stopResult is CameraResult.Success)
        assertEquals(StreamLifecycleState.IDLE, repository.getCameraState(deviceId1)?.lifecycleState)

        // Second session: start again
        val startResult2 = repository.startCamera(deviceId1, config, session)
        assertTrue(startResult2 is CameraResult.Success)

        // Start commands must have different command IDs because the first operation completed successfully
        val startCmd1 = fakeSessionManager.sentCommands[0] as TrackerCommand.StartCamera
        val startCmd2 = fakeSessionManager.sentCommands[2] as TrackerCommand.StartCamera
        assertTrue(startCmd1.commandId != startCmd2.commandId)
    }

    @Test
    fun `stopCamera failure retains commandId for retry while success resets to IDLE with clean state`() = runTest {
        val session = createValidSession()
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)

        repository.startCamera(deviceId1, config, session)
        assertEquals(StreamLifecycleState.STREAMING, repository.getCameraState(deviceId1)?.lifecycleState)

        // Stop attempt fails with timeout
        fakeSessionManager.nextCommandResult = CommandResult.Timeout("stop-cmd", deviceId1)
        val failedStop = repository.stopCamera(deviceId1, session)
        assertTrue(failedStop is CameraResult.CommandFailed)
        assertEquals(StreamLifecycleState.ERROR, repository.getCameraState(deviceId1)?.lifecycleState)

        val firstStopCommandId = (fakeSessionManager.sentCommands.last() as TrackerCommand.StopCamera).commandId

        // Retry stop
        fakeSessionManager.nextCommandResult = CommandResult.Accepted(firstStopCommandId, deviceId1)
        val successfulStop = repository.stopCamera(deviceId1, session)
        assertTrue(successfulStop is CameraResult.Success)
        assertEquals(StreamLifecycleState.IDLE, repository.getCameraState(deviceId1)?.lifecycleState)

        val retryStopCommandId = (fakeSessionManager.sentCommands.last() as TrackerCommand.StopCamera).commandId
        assertEquals(firstStopCommandId, retryStopCommandId)
    }

    @Test
    fun `startCamera with different capture config after ERROR generates fresh commandId`() = runTest {
        val session = createValidSession()
        val config1 = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)
        val config2 = QualityProfile.LOW.toCaptureConfig(facing = CameraFacing.FRONT)

        // First attempt with config1 times out
        fakeSessionManager.nextCommandResult = CommandResult.Timeout("cfg1-cmd", deviceId1)
        val result1 = repository.startCamera(deviceId1, config1, session)
        assertTrue(result1 is CameraResult.CommandFailed)
        val firstCommandId = (fakeSessionManager.sentCommands.last() as TrackerCommand.StartCamera).commandId

        // User changes mind and initiates with config2 -> must be treated as a new operation
        fakeSessionManager.nextCommandResult = CommandResult.Accepted("new-cmd", deviceId1)
        val result2 = repository.startCamera(deviceId1, config2, session)
        assertTrue(result2 is CameraResult.Success)
        val secondCommandId = (fakeSessionManager.sentCommands.last() as TrackerCommand.StartCamera).commandId

        assertTrue(firstCommandId != secondCommandId)
    }
}

/**
 * Fake session manager for testing command dispatch outcomes.
 */
class FakeTrackerSessionManager : TrackerSessionManager {
    private val sessions = mutableMapOf<TrackerId, MutableStateFlow<TrackerSessionState>>()
    var nextCommandResult: CommandResult? = null
    var onSendCommand: (suspend (TrackerCommand) -> Unit)? = null
    val sentCommands = mutableListOf<TrackerCommand>()

    fun setCapabilities(deviceId: TrackerId, capabilities: TrackerCapabilities) {
        val state = sessions.getOrPut(deviceId) {
            MutableStateFlow(TrackerSessionState(deviceId = deviceId))
        }
        state.value = state.value.copy(
            capabilities = capabilities,
            connectionState = ConnectionState.CONNECTED
        )
    }

    override fun registerTracker(deviceId: TrackerId, displayName: String) {
        sessions.getOrPut(deviceId) {
            MutableStateFlow(TrackerSessionState(deviceId = deviceId, displayName = displayName))
        }
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

    override fun hasSession(deviceId: TrackerId): Boolean = sessions.containsKey(deviceId)

    override fun listSessions(): List<TrackerId> = sessions.keys.toList()

    override suspend fun startConnection(deviceId: TrackerId) {}

    override suspend fun disconnect(deviceId: TrackerId) {}

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        sentCommands.add(command)
        onSendCommand?.invoke(command)
        return nextCommandResult ?: CommandResult.Accepted(command.commandId, command.deviceId)
    }
}
