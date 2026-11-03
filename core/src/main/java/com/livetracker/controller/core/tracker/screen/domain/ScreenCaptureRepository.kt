package com.livetracker.controller.core.tracker.screen.domain

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface ScreenCaptureRepository {
    fun observeScreenState(deviceId: TrackerId): Flow<ScreenCaptureState>
    suspend fun getScreenState(deviceId: TrackerId): ScreenCaptureState?
    suspend fun startScreenCapture(deviceId: TrackerId, qualityProfile: String = "BALANCED", session: Session?): Result<Unit>
    suspend fun stopScreenCapture(deviceId: TrackerId, session: Session?): Result<Unit>
}
