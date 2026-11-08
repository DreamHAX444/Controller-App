package com.livetracker.controller.core.tracker.camera

import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraError
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.domain.TrackerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelTest {

    @Test
    fun `CameraFacing enum contains FRONT and BACK`() {
        val facings = CameraFacing.values().map { it.name }
        assertTrue(facings.contains("FRONT"))
        assertTrue(facings.contains("BACK"))
        assertEquals(2, CameraFacing.values().size)
    }

    @Test
    fun `CameraResolution validates positive dimensions`() {
        val res = CameraResolution(1920, 1080)
        assertEquals(1920, res.width)
        assertEquals(1080, res.height)
        assertEquals(16f / 9f, res.aspectRatio, 0.01f)
        assertEquals("1920x1080", res.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CameraResolution rejects non-positive width`() {
        CameraResolution(0, 1080)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CameraResolution rejects negative height`() {
        CameraResolution(1920, -1)
    }

    @Test
    fun `CameraResolution provides standard constants`() {
        assertEquals(320, CameraResolution.QVGA.width)
        assertEquals(240, CameraResolution.QVGA.height)

        assertEquals(640, CameraResolution.VGA.width)
        assertEquals(480, CameraResolution.VGA.height)

        assertEquals(1280, CameraResolution.HD_720P.width)
        assertEquals(720, CameraResolution.HD_720P.height)

        assertEquals(1920, CameraResolution.FULL_HD_1080P.width)
        assertEquals(1080, CameraResolution.FULL_HD_1080P.height)

        assertEquals(3840, CameraResolution.ULTRA_HD_4K.width)
        assertEquals(2160, CameraResolution.ULTRA_HD_4K.height)

        assertEquals(5, CameraResolution.SUPPORTED_RESOLUTIONS.size)
    }

    @Test
    fun `QualityProfile defines correct resolution, fps, and bitrate defaults`() {
        assertEquals(CameraResolution.VGA, QualityProfile.LOW.resolution)
        assertEquals(15, QualityProfile.LOW.targetFps)
        assertEquals(500, QualityProfile.LOW.targetBitrateKbps)

        assertEquals(CameraResolution.HD_720P, QualityProfile.MEDIUM.resolution)
        assertEquals(24, QualityProfile.MEDIUM.targetFps)
        assertEquals(1500, QualityProfile.MEDIUM.targetBitrateKbps)

        assertEquals(CameraResolution.FULL_HD_1080P, QualityProfile.HIGH.resolution)
        assertEquals(30, QualityProfile.HIGH.targetFps)
        assertEquals(3000, QualityProfile.HIGH.targetBitrateKbps)

        val config = QualityProfile.HIGH.toCaptureConfig(facing = CameraFacing.FRONT, cameraId = "cam-0")
        assertEquals(CameraResolution.FULL_HD_1080P, config.resolution)
        assertEquals(30, config.fps)
        assertEquals(3000, config.bitrateKbps)
        assertEquals(CameraFacing.FRONT, config.facing)
        assertEquals("cam-0", config.cameraId)
    }

    @Test
    fun `CameraCaptureConfig validates positive FPS and bitrate`() {
        val config = CameraCaptureConfig(
            resolution = CameraResolution.HD_720P,
            fps = 30,
            bitrateKbps = 2000,
            facing = CameraFacing.BACK
        )
        assertEquals(30, config.fps)
        assertEquals(2000, config.bitrateKbps)
        assertEquals(CameraFacing.BACK, config.facing)
        assertEquals(QualityProfile.MEDIUM.toCaptureConfig(), CameraCaptureConfig.DEFAULT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CameraCaptureConfig rejects zero or negative FPS`() {
        CameraCaptureConfig(CameraResolution.HD_720P, fps = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CameraCaptureConfig rejects negative bitrate`() {
        CameraCaptureConfig(CameraResolution.HD_720P, fps = 30, bitrateKbps = -100)
    }

    @Test
    fun `ActualCaptureConfig accepts non-negative FPS and bitrate`() {
        val actual = ActualCaptureConfig(
            resolution = CameraResolution.HD_720P,
            fps = 23.8,
            bitrateKbps = 1420,
            facing = CameraFacing.BACK,
            cameraId = "0"
        )
        assertEquals(23.8, actual.fps, 0.001)
        assertEquals(1420, actual.bitrateKbps)
        assertEquals(CameraFacing.BACK, actual.facing)
        assertEquals("0", actual.cameraId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ActualCaptureConfig rejects negative FPS`() {
        ActualCaptureConfig(CameraResolution.HD_720P, fps = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ActualCaptureConfig rejects negative bitrate`() {
        ActualCaptureConfig(CameraResolution.HD_720P, fps = 24.0, bitrateKbps = -5)
    }

    @Test
    fun `StreamLifecycleState enforces valid transition capabilities`() {
        assertTrue(StreamLifecycleState.IDLE.canStart)
        assertFalse(StreamLifecycleState.IDLE.canStop)
        assertFalse(StreamLifecycleState.IDLE.canSwitch)
        assertFalse(StreamLifecycleState.IDLE.isActive)

        assertFalse(StreamLifecycleState.STARTING.canStart)
        assertTrue(StreamLifecycleState.STARTING.canStop)
        assertFalse(StreamLifecycleState.STARTING.canSwitch)
        assertFalse(StreamLifecycleState.STARTING.isActive)

        assertFalse(StreamLifecycleState.STREAMING.canStart)
        assertTrue(StreamLifecycleState.STREAMING.canStop)
        assertTrue(StreamLifecycleState.STREAMING.canSwitch)
        assertTrue(StreamLifecycleState.STREAMING.isActive)

        assertFalse(StreamLifecycleState.SWITCHING.canStart)
        assertTrue(StreamLifecycleState.SWITCHING.canStop)
        assertFalse(StreamLifecycleState.SWITCHING.canSwitch)
        assertTrue(StreamLifecycleState.SWITCHING.isActive)

        assertFalse(StreamLifecycleState.STOPPING.canStart)
        assertFalse(StreamLifecycleState.STOPPING.canStop)
        assertFalse(StreamLifecycleState.STOPPING.canSwitch)
        assertFalse(StreamLifecycleState.STOPPING.isActive)

        assertTrue(StreamLifecycleState.ERROR.canStart)
        assertFalse(StreamLifecycleState.ERROR.canStop)
        assertFalse(StreamLifecycleState.ERROR.canSwitch)
        assertFalse(StreamLifecycleState.ERROR.isActive)
    }

    @Test
    fun `TrackerCameraState initial state and default invariants`() {
        val devId = TrackerId("tracker-001")
        val state = TrackerCameraState.initial(devId, timestamp = 12345L)

        assertEquals(devId, state.deviceId)
        assertEquals(StreamLifecycleState.IDLE, state.lifecycleState)
        assertNull(state.requestedConfig)
        assertNull(state.actualConfig)
        assertFalse(state.isSimulated)
        assertNull(state.simulationLabel)
        assertNull(state.lastError)
        assertEquals(12345L, state.lastUpdatedTimestamp)
        assertFalse(state.isStreaming)
    }

    @Test
    fun `CameraError types provide clear diagnostic messages`() {
        val unsupported = CameraError.UnsupportedCapability()
        val denied = CameraError.PermissionDenied()
        val notConnected = CameraError.DeviceNotConnected()
        val timeout = CameraError.Timeout()
        val failure = CameraError.CommandFailure("Camera sensor failed", RuntimeException("Hardware error"))

        assertNotNull(unsupported.message)
        assertNotNull(denied.message)
        assertNotNull(notConnected.message)
        assertEquals("Camera command timed out", timeout.message)
        assertEquals("Camera sensor failed", failure.message)
        assertNotNull(failure.cause)
    }
}
