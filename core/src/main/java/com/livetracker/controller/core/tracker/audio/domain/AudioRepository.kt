package com.livetracker.controller.core.tracker.audio.domain

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface AudioRepository {
    fun observeAudioState(deviceId: TrackerId): Flow<AudioState>
    suspend fun getAudioState(deviceId: TrackerId): AudioState?
    suspend fun startAudio(deviceId: TrackerId, session: Session?): Result<Unit>
    suspend fun stopAudio(deviceId: TrackerId, session: Session?): Result<Unit>
    suspend fun setVolume(deviceId: TrackerId, volume: Float)
    suspend fun setMuted(deviceId: TrackerId, muted: Boolean)
}
