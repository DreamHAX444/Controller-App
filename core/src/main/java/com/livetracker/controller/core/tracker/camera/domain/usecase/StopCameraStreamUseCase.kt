package com.livetracker.controller.core.tracker.camera.domain.usecase

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * Requests stopping the active camera stream for a tracker device.
 */
class StopCameraStreamUseCase(
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(
        deviceId: TrackerId,
        session: Session? = null
    ): CameraResult {
        return cameraRepository.stopCamera(deviceId, session)
    }
}
