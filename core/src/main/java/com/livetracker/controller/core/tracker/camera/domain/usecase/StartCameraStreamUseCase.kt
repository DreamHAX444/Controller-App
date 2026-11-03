package com.livetracker.controller.core.tracker.camera.domain.usecase

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * Requests starting the camera stream for a tracker device with specified configuration.
 */
class StartCameraStreamUseCase(
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(
        deviceId: TrackerId,
        config: CameraCaptureConfig = CameraCaptureConfig.DEFAULT,
        session: Session? = null
    ): CameraResult {
        return cameraRepository.startCamera(deviceId, config, session)
    }
}
