package com.livetracker.controller.core.tracker.camera.domain.usecase

import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

/**
 * Observes the reactive camera stream state for a specific tracker device.
 */
class ObserveCameraStreamUseCase(
    private val cameraRepository: CameraRepository
) {
    operator fun invoke(deviceId: TrackerId): Flow<TrackerCameraState> {
        return cameraRepository.observeCameraState(deviceId)
    }
}
