package com.livetracker.controller.core.tracker.camera.domain.usecase

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * Requests switching the active camera lens/facing on a streaming tracker device.
 */
class SwitchCameraFacingUseCase(
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(
        deviceId: TrackerId,
        facing: CameraFacing,
        session: Session? = null,
        cameraId: String? = null
    ): CameraResult {
        return cameraRepository.switchCamera(deviceId, facing, session, cameraId)
    }
}
