package com.livetracker.controller.core.tracker.camera

import com.livetracker.controller.core.tracker.camera.data.SimulatedCameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.usecase.ObserveCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.StartCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.StopCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.SwitchCameraFacingUseCase
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraUseCasesTest {

    private val deviceId = TrackerId("tracker-uc-1")
    private lateinit var repository: SimulatedCameraRepository
    private lateinit var observeUseCase: ObserveCameraStreamUseCase
    private lateinit var startUseCase: StartCameraStreamUseCase
    private lateinit var stopUseCase: StopCameraStreamUseCase
    private lateinit var switchUseCase: SwitchCameraFacingUseCase

    @Before
    fun setUp() {
        repository = SimulatedCameraRepository(
            supportedDevices = setOf(deviceId),
            authorizedDevices = setOf(deviceId)
        )
        observeUseCase = ObserveCameraStreamUseCase(repository)
        startUseCase = StartCameraStreamUseCase(repository)
        stopUseCase = StopCameraStreamUseCase(repository)
        switchUseCase = SwitchCameraFacingUseCase(repository)
    }

    @Test
    fun `ObserveCameraStreamUseCase emits initial state from repository`() = runTest {
        val state = observeUseCase(deviceId).first()
        assertEquals(deviceId, state.deviceId)
        assertEquals(StreamLifecycleState.IDLE, state.lifecycleState)
        assertTrue(state.isSimulated)
    }

    @Test
    fun `StartCameraStreamUseCase initiates stream successfully`() = runTest {
        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.BACK)
        val result = startUseCase(deviceId, config)

        assertTrue(result is CameraResult.Success)
        val success = result as CameraResult.Success
        assertEquals(StreamLifecycleState.STREAMING, success.state.lifecycleState)
        assertEquals(CameraResolution.FULL_HD_1080P, success.state.requestedConfig?.resolution)
        assertNull(success.state.actualConfig) // Never fabricated
    }

    @Test
    fun `SwitchCameraFacingUseCase switches facing on active stream`() = runTest {
        startUseCase(deviceId, QualityProfile.MEDIUM.toCaptureConfig(facing = CameraFacing.BACK))

        val result = switchUseCase(deviceId, CameraFacing.FRONT)
        assertTrue(result is CameraResult.Success)
        val success = result as CameraResult.Success
        assertEquals(CameraFacing.FRONT, success.state.requestedConfig?.facing)
    }

    @Test
    fun `StopCameraStreamUseCase terminates stream and returns IDLE state`() = runTest {
        startUseCase(deviceId)
        val result = stopUseCase(deviceId)

        assertTrue(result is CameraResult.Success)
        val success = result as CameraResult.Success
        assertEquals(StreamLifecycleState.IDLE, success.state.lifecycleState)
    }
}
