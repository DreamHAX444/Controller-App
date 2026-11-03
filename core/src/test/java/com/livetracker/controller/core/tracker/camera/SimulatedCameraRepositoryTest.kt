package com.livetracker.controller.core.tracker.camera

import com.livetracker.controller.core.tracker.camera.data.SimulatedCameraRepository
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraError
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimulatedCameraRepositoryTest {

    private val device1 = TrackerId("sim-tracker-1")
    private val device2 = TrackerId("sim-tracker-2")
    private lateinit var simulatedRepo: SimulatedCameraRepository

    @Before
    fun setUp() {
        simulatedRepo = SimulatedCameraRepository(
            simulationLabel = "Test Deterministic Simulator",
            supportedDevices = setOf(device1),
            authorizedDevices = setOf(device1)
        )
    }

    @Test
    fun `critical constraint - states are explicitly identified as simulated and never real`() = runTest {
        val initial = simulatedRepo.observeCameraState(device1).first()
        assertTrue("isSimulated must be true for simulated repository", initial.isSimulated)
        assertEquals("Test Deterministic Simulator", initial.simulationLabel)
        assertNull("actualConfig must not be fabricated", initial.actualConfig)

        val result = simulatedRepo.startCamera(device1)
        assertTrue(result is CameraResult.Success)
        val streamingState = (result as CameraResult.Success).state

        assertTrue("isSimulated must be true during streaming", streamingState.isSimulated)
        assertEquals("Test Deterministic Simulator", streamingState.simulationLabel)
        assertNull("actualConfig must remain null until explicitly updated", streamingState.actualConfig)
    }

    @Test
    fun `deterministic state lifecycle flow`() = runTest {
        // 1. Initial IDLE
        val initial = simulatedRepo.getCameraState(device1) ?: simulatedRepo.observeCameraState(device1).first()
        assertEquals(StreamLifecycleState.IDLE, initial.lifecycleState)

        // 2. Start Camera
        val startResult = simulatedRepo.startCamera(
            deviceId = device1,
            config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)
        )
        assertTrue(startResult is CameraResult.Success)
        val activeState = (startResult as CameraResult.Success).state
        assertEquals(StreamLifecycleState.STREAMING, activeState.lifecycleState)
        assertEquals(CameraFacing.BACK, activeState.requestedConfig?.facing)

        // 3. Switch Camera
        val switchResult = simulatedRepo.switchCamera(device1, CameraFacing.FRONT)
        assertTrue(switchResult is CameraResult.Success)
        val switchedState = (switchResult as CameraResult.Success).state
        assertEquals(StreamLifecycleState.STREAMING, switchedState.lifecycleState)
        assertEquals(CameraFacing.FRONT, switchedState.requestedConfig?.facing)

        // 4. Stop Camera
        val stopResult = simulatedRepo.stopCamera(device1)
        assertTrue(stopResult is CameraResult.Success)
        val stoppedState = (stopResult as CameraResult.Success).state
        assertEquals(StreamLifecycleState.IDLE, stoppedState.lifecycleState)
        assertNull(stoppedState.requestedConfig)
        assertNull(stoppedState.actualConfig)
    }

    @Test
    fun `invalid transitions are rejected deterministically`() = runTest {
        // Cannot stop when IDLE
        val stopResult = simulatedRepo.stopCamera(device1)
        assertTrue(stopResult is CameraResult.InvalidStateTransition)

        // Cannot switch when IDLE
        val switchResult = simulatedRepo.switchCamera(device1, CameraFacing.FRONT)
        assertTrue(switchResult is CameraResult.InvalidStateTransition)

        // Start streaming
        simulatedRepo.startCamera(device1)

        // Cannot start when already STREAMING
        val doubleStart = simulatedRepo.startCamera(device1)
        assertTrue(doubleStart is CameraResult.InvalidStateTransition)
    }

    @Test
    fun `unsupported devices yield UnsupportedDevice result and set error state`() = runTest {
        val repo = SimulatedCameraRepository(
            simulationLabel = "Test Deterministic Simulator",
            supportedDevices = setOf(device1), // device2 is not supported
            authorizedDevices = setOf(device1, device2) // device2 is authorized
        )
        val result = repo.startCamera(device2)

        assertTrue(result is CameraResult.UnsupportedDevice)
        val state = repo.getCameraState(device2)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.UnsupportedCapability)
    }

    @Test
    fun `unauthorized devices yield PermissionDenied result and set error state`() = runTest {
        val restrictedRepo = SimulatedCameraRepository(
            supportedDevices = setOf(device1, device2),
            authorizedDevices = setOf(device1) // device2 not authorized
        )

        val result = restrictedRepo.startCamera(device2)

        assertTrue(result is CameraResult.PermissionDenied)
        val state = restrictedRepo.getCameraState(device2)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.PermissionDenied)
    }

    @Test
    fun `injected command failure returns CommandFailed and transitions to ERROR`() = runTest {
        simulatedRepo.simulatedCommandFailure = "Simulated hardware fault"

        val result = simulatedRepo.startCamera(device1)

        assertTrue(result is CameraResult.CommandFailed)
        assertEquals("Simulated hardware fault", (result as CameraResult.CommandFailed).reason)

        val state = simulatedRepo.getCameraState(device1)
        assertEquals(StreamLifecycleState.ERROR, state?.lifecycleState)
        assertTrue(state?.lastError is CameraError.CommandFailure)
    }

    @Test
    fun `explicit telemetry ingestion updates actualConfig without fabrication`() = runTest {
        simulatedRepo.startCamera(device1, QualityProfile.MEDIUM.toCaptureConfig())
        var state = simulatedRepo.getCameraState(device1)
        assertNull(state?.actualConfig)

        val telemetry = ActualCaptureConfig(
            resolution = CameraResolution.HD_720P,
            fps = 24.0,
            bitrateKbps = 1500,
            facing = CameraFacing.BACK
        )
        simulatedRepo.updateActualConfig(device1, telemetry)

        state = simulatedRepo.getCameraState(device1)
        assertNotNull(state?.actualConfig)
        assertEquals(24.0, state?.actualConfig?.fps ?: 0.0, 0.001)
        assertEquals(1500, state?.actualConfig?.bitrateKbps)
    }
}
