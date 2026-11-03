package com.livetracker.controller.core.tracker.audio.data

import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.audio.domain.AudioRepository
import com.livetracker.controller.core.tracker.audio.domain.AudioState
import com.livetracker.controller.core.tracker.audio.domain.AudioStatus
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

interface AudioPlayer {
    fun start()
    fun playPcm(pcm: ByteArray)
    fun setVolume(volume: Float)
    fun stop()
}

class DefaultAudioRepository(
    private val sessionManager: TrackerSessionManager? = null,
    private val authEngine: AuthorizationEngine? = null,
    private val clientProvider: (() -> SupabaseClient)? = null,
    private val audioPlayerFactory: (() -> AudioPlayer)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : AudioRepository {

    private val statesFlow = MutableStateFlow<Map<TrackerId, AudioState>>(emptyMap())
    private val mutex = Mutex()
    private val activePlayers = mutableMapOf<TrackerId, AudioPlayer>()
    private val activeJobs = mutableMapOf<TrackerId, Pair<Job, RealtimeChannel?>>()

    override fun observeAudioState(deviceId: TrackerId): Flow<AudioState> {
        return statesFlow.map { map ->
            map[deviceId] ?: AudioState(deviceId = deviceId)
        }.distinctUntilChanged()
    }

    override suspend fun getAudioState(deviceId: TrackerId): AudioState? {
        return mutex.withLock { statesFlow.value[deviceId] }
    }

    override suspend fun startAudio(deviceId: TrackerId, session: Session?): Result<Unit> = mutex.withLock {
        // Step 1: Authorization Gate
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.MICROPHONE_AUDIO,
                action = Action.READ,
                currentTime = timeProvider()
            )
            val fallbackAuth = if (authResult !is AuthorizationResult.Allowed) {
                authEngine.authorize(
                    session = session,
                    deviceId = deviceId.value,
                    service = ServicePermission.DEVICE_AUDIO,
                    action = Action.READ,
                    currentTime = timeProvider()
                )
            } else authResult

            if (fallbackAuth !is AuthorizationResult.Allowed) {
                val err = "Audio access denied: ${fallbackAuth::class.simpleName}"
                updateState(deviceId) { it.copy(status = AudioStatus.ERROR, errorMessage = err) }
                return Result.failure(SecurityException(err))
            }
        }

        val sessionId = UUID.randomUUID().toString()
        updateState(deviceId) {
            it.copy(
                sessionId = sessionId,
                status = AudioStatus.CONNECTING,
                errorMessage = null,
                lastUpdated = timeProvider()
            )
        }

        // Step 2: Initialize Audio Player
        val player = audioPlayerFactory?.invoke()
        if (player != null) {
            player.start()
            val currentVol = statesFlow.value[deviceId]?.volume ?: 1.0f
            val isMuted = statesFlow.value[deviceId]?.isMuted ?: false
            player.setVolume(if (isMuted) 0f else currentVol)
            activePlayers[deviceId] = player
        }

        // Step 3: Subscribe to Supabase Realtime Channel
        var channel: RealtimeChannel? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                val client = clientProvider?.invoke()
                if (client != null) {
                    val ch = client.realtime.channel("media-audio-$sessionId")
                    channel = ch
                    ch.subscribe()
                    updateState(deviceId) { it.copy(status = AudioStatus.STREAMING, lastUpdated = timeProvider()) }

                    ch.broadcastFlow<JsonObject>(event = "audio_frame").collect { frameObj ->
                        try {
                            val base64 = frameObj["payload"]?.jsonPrimitive?.content
                                ?: frameObj["pcmBytes"]?.jsonPrimitive?.content
                            if (!base64.isNullOrBlank()) {
                                val bytes = java.util.Base64.getDecoder().decode(base64)
                                player?.playPcm(bytes)
                                val (amp, bars) = computeAudioWaveform(bytes)
                                updateState(deviceId) {
                                    it.copy(
                                        amplitude = amp,
                                        waveformBars = bars,
                                        lastUpdated = timeProvider()
                                    )
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    updateState(deviceId) { it.copy(status = AudioStatus.STREAMING, lastUpdated = timeProvider()) }
                }
            } catch (e: Exception) {
                updateState(deviceId) {
                    it.copy(
                        status = AudioStatus.ERROR,
                        errorMessage = e.message ?: "Audio stream error",
                        lastUpdated = timeProvider()
                    )
                }
            }
        }
        activeJobs[deviceId] = Pair(job, channel)

        // Step 4: Dispatch command to Tracker
        val command = TrackerCommand.StartAudio(
            deviceId = deviceId,
            sessionId = sessionId
        )
        sessionManager?.sendCommand(command)

        Result.success(Unit)
    }

    override suspend fun stopAudio(deviceId: TrackerId, session: Session?): Result<Unit> = mutex.withLock {
        val currentState = statesFlow.value[deviceId]
        val sessionId = currentState?.sessionId

        // Cancel job and channel
        activeJobs.remove(deviceId)?.let { (job, channel) ->
            job.cancel()
            scope.launch(Dispatchers.IO) {
                try {
                    channel?.unsubscribe()
                } catch (_: Exception) {}
                try {
                    if (channel != null) {
                        clientProvider?.invoke()?.realtime?.removeChannel(channel)
                    }
                } catch (_: Exception) {}
            }
        }

        // Stop player
        activePlayers.remove(deviceId)?.stop()

        // Send Stop Command
        val command = TrackerCommand.StopAudio(deviceId = deviceId)
        sessionManager?.sendCommand(command)

        updateState(deviceId) {
            it.copy(
                status = AudioStatus.STOPPED,
                sessionId = null,
                amplitude = 0f,
                waveformBars = emptyList(),
                lastUpdated = timeProvider()
            )
        }

        Result.success(Unit)
    }

    override suspend fun setVolume(deviceId: TrackerId, volume: Float): Unit = mutex.withLock {
        val clamped = volume.coerceIn(0f, 1f)
        updateState(deviceId) { it.copy(volume = clamped) }
        val isMuted = statesFlow.value[deviceId]?.isMuted ?: false
        activePlayers[deviceId]?.setVolume(if (isMuted) 0f else clamped)
    }

    override suspend fun setMuted(deviceId: TrackerId, muted: Boolean): Unit = mutex.withLock {
        updateState(deviceId) { it.copy(isMuted = muted) }
        val vol = statesFlow.value[deviceId]?.volume ?: 1.0f
        activePlayers[deviceId]?.setVolume(if (muted) 0f else vol)
    }

    private fun updateState(deviceId: TrackerId, transform: (AudioState) -> AudioState) {
        statesFlow.update { map ->
            val current = map[deviceId] ?: AudioState(deviceId = deviceId)
            map + (deviceId to transform(current))
        }
    }

    companion object {
        fun computeAudioWaveform(bytes: ByteArray, barCount: Int = 20): Pair<Float, List<Float>> {
            val sampleCount = bytes.size / 2
            if (sampleCount <= 0) return Pair(0f, List(barCount) { 0.08f })

            var totalSumSquares = 0.0
            val samplesPerBar = (sampleCount / barCount).coerceAtLeast(1)
            val bars = ArrayList<Float>(barCount)

            for (b in 0 until barCount) {
                val startIdx = b * samplesPerBar
                val endIdx = ((b + 1) * samplesPerBar).coerceAtMost(sampleCount)
                var chunkSumSquares = 0.0
                var count = 0
                for (s in startIdx until endIdx) {
                    val low = bytes[s * 2].toInt() and 0xFF
                    val high = bytes[s * 2 + 1].toInt()
                    val sample = ((high shl 8) or low).toShort().toDouble()
                    chunkSumSquares += sample * sample
                    totalSumSquares += sample * sample
                    count++
                }
                val chunkRms = if (count > 0) Math.sqrt(chunkSumSquares / count) else 0.0
                // High-sensitivity logarithmic audio normalization (dBFS) for vibrant waveform visualization:
                val dynamicBar = if (chunkRms > 25.0) {
                    val rmsDb = 20.0 * Math.log10(chunkRms / 32767.0) // approx -62 dB to 0 dB
                    // Map -55 dBFS (speech threshold) to -5 dBFS into 0.0 .. 1.0 range
                    val normalized = ((rmsDb + 55.0) / 50.0).coerceIn(0.0, 1.0)
                    (0.12 + Math.pow(normalized, 0.65) * 0.88).toFloat().coerceIn(0.12f, 1.0f)
                } else {
                    0.08f
                }
                bars.add(dynamicBar)
            }

            val totalRms = Math.sqrt(totalSumSquares / sampleCount)
            val overallAmp = if (totalRms > 25.0) {
                val rmsDb = 20.0 * Math.log10(totalRms / 32767.0)
                val normalized = ((rmsDb + 55.0) / 50.0).coerceIn(0.0, 1.0)
                Math.pow(normalized, 0.65).toFloat().coerceIn(0f, 1.0f)
            } else {
                0f
            }
            return Pair(overallAmp, bars)
        }
    }
}
