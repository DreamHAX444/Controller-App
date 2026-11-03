package com.livetracker.controller.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

data class ActiveAlarmState(
    val alertId: String,
    val geofenceId: String?,
    val geofenceName: String,
    val deviceId: String,
    val soundProfile: AlertSoundProfile,
    val durationSeconds: Int,
    val startedAtMs: Long,
    val timeRemainingSeconds: Int
)

class SirenAlertEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _activeAlarm = MutableStateFlow<ActiveAlarmState?>(null)
    val activeAlarm: StateFlow<ActiveAlarmState?> = _activeAlarm.asStateFlow()

    private var playbackJob: Job? = null
    private var countdownJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Triggers the alarm siren with the specified profile and duration.
     * Guarantees continuous looping without cutting off prematurely until duration expires
     * or dismissAlarm() is invoked.
     */
    @Synchronized
    fun triggerAlarm(
        alertId: String,
        geofenceId: String?,
        geofenceName: String,
        deviceId: String,
        soundProfile: AlertSoundProfile,
        durationSeconds: Int = 30,
        enableVibration: Boolean = true
    ) {
        stopCurrentPlayback()

        val effectiveDuration = if (durationSeconds <= 0) 30 else durationSeconds
        val startTime = System.currentTimeMillis()

        _activeAlarm.value = ActiveAlarmState(
            alertId = alertId,
            geofenceId = geofenceId,
            geofenceName = geofenceName,
            deviceId = deviceId,
            soundProfile = soundProfile,
            durationSeconds = effectiveDuration,
            startedAtMs = startTime,
            timeRemainingSeconds = effectiveDuration
        )

        // Generate synthetic PCM samples for the selected profile
        val pcmData = generateSoundProfilePcm(soundProfile)

        // Start vibration
        if (enableVibration) {
            startVibration()
        }

        // Countdown timer coroutine
        countdownJob = scope.launch(Dispatchers.Default) {
            var remaining = effectiveDuration
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining--
                val current = _activeAlarm.value ?: break
                _activeAlarm.value = current.copy(timeRemainingSeconds = remaining)
            }
            if (isActive) {
                dismissAlarm()
            }
        }

        // Continuous audio playback loop
        playbackJob = scope.launch(Dispatchers.IO) {
            playLoopingTrack(pcmData)
        }
    }

    /**
     * Preview sound profile for testing in settings or geofence editor.
     */
    @Synchronized
    fun testSoundProfile(soundProfile: AlertSoundProfile, durationSeconds: Int = 5) {
        triggerAlarm(
            alertId = "preview_${System.currentTimeMillis()}",
            geofenceId = null,
            geofenceName = "Preview: ${soundProfile.displayName}",
            deviceId = "test",
            soundProfile = soundProfile,
            durationSeconds = durationSeconds,
            enableVibration = true
        )
    }

    /**
     * Dismisses and immediately stops any ongoing siren, vibration, and countdown.
     */
    @Synchronized
    fun dismissAlarm() {
        stopCurrentPlayback()
        _activeAlarm.value = null
    }

    fun isPlaying(): Boolean = _activeAlarm.value != null

    private fun stopCurrentPlayback() {
        countdownJob?.cancel()
        countdownJob = null

        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        audioTrack = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }

    private fun playLoopingTrack(pcmData: ShortArray) {
        val sampleRate = 16000
        val bufferSizeBytes = pcmData.size * 2

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_ALARM,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack = track
        try {
            track.play()
            while (playbackJob?.isActive == true) {
                val written = track.write(pcmData, 0, pcmData.size)
                if (written < 0) break
            }
        } catch (_: Exception) {
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (_: Exception) {}
            if (audioTrack == track) {
                audioTrack = null
            }
        }
    }

    private fun startVibration() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            val timings = longArrayOf(0, 400, 200, 400, 200) // Pattern
            val amplitudes = intArrayOf(0, 255, 0, 255, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, 0) // repeat index 0
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, 0)
            }
        } catch (_: Exception) {}
    }

    /**
     * Synthesizes 16kHz mono 16-bit PCM for the requested profile.
     * Completely self-contained, offline, deterministic and lightweight (Ponytail architecture).
     */
    private fun generateSoundProfilePcm(profile: AlertSoundProfile): ShortArray {
        val sampleRate = 16000
        return when (profile) {
            AlertSoundProfile.TACTICAL_SIREN -> {
                // Dual-tone wailing frequency sweep between 600Hz and 1200Hz over 1.2 seconds
                val durationSec = 1.2
                val numSamples = (sampleRate * durationSec).toInt()
                val samples = ShortArray(numSamples)
                var phase = 0.0
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // Frequency oscillates sinusoidally between 600Hz and 1200Hz
                    val freq = 900.0 + 300.0 * sin(2.0 * PI * t / durationSec)
                    phase += 2.0 * PI * freq / sampleRate
                    val sample = (sin(phase) * 0.9 * Short.MAX_VALUE).toInt()
                    samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                samples
            }
            AlertSoundProfile.RAPID_KLAXON -> {
                // Alternating two sharp frequencies: 880Hz (160ms) and 587Hz (160ms)
                val tone1Duration = 0.16
                val tone2Duration = 0.16
                val totalDuration = tone1Duration + tone2Duration
                val numSamples = (sampleRate * totalDuration).toInt()
                val samples = ShortArray(numSamples)
                var phase = 0.0
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if (t < tone1Duration) 880.0 else 587.0
                    phase += 2.0 * PI * freq / sampleRate
                    // Add mild harmonic for punchy klaxon timbre
                    val wave = 0.75 * sin(phase) + 0.25 * sin(2.0 * phase)
                    val sample = (wave * 0.9 * Short.MAX_VALUE).toInt()
                    samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                samples
            }
            AlertSoundProfile.PULSING_ALARM -> {
                // Urgent high-pitch repeating burst: 1200Hz for 120ms, silence for 120ms
                val totalDuration = 0.24
                val activeDuration = 0.12
                val numSamples = (sampleRate * totalDuration).toInt()
                val samples = ShortArray(numSamples)
                var phase = 0.0
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    if (t < activeDuration) {
                        phase += 2.0 * PI * 1200.0 / sampleRate
                        val sample = (sin(phase) * 0.95 * Short.MAX_VALUE).toInt()
                        samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    } else {
                        samples[i] = 0
                    }
                }
                samples
            }
            AlertSoundProfile.MILITARY_HORN -> {
                // Low brass horn: 350Hz + 700Hz + 1050Hz, 500ms on, 250ms off
                val totalDuration = 0.75
                val activeDuration = 0.50
                val numSamples = (sampleRate * totalDuration).toInt()
                val samples = ShortArray(numSamples)
                var phase = 0.0
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    if (t < activeDuration) {
                        phase += 2.0 * PI * 350.0 / sampleRate
                        val wave = 0.55 * sin(phase) + 0.30 * sin(2.0 * phase) + 0.15 * sin(3.0 * phase)
                        val sample = (wave * 0.9 * Short.MAX_VALUE).toInt()
                        samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    } else {
                        samples[i] = 0
                    }
                }
                samples
            }
            AlertSoundProfile.DISCREET_CHIME -> {
                // Gentle chime: 659Hz (E5) fading to 880Hz (A5), smooth decay envelope
                val durationSec = 1.0
                val numSamples = (sampleRate * durationSec).toInt()
                val samples = ShortArray(numSamples)
                var phase = 0.0
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if (t < 0.3) 659.0 else 880.0
                    phase += 2.0 * PI * freq / sampleRate
                    // Exponential decay envelope
                    val envelope = kotlin.math.exp(-3.0 * (t % 0.5))
                    val sample = (sin(phase) * envelope * 0.8 * Short.MAX_VALUE).toInt()
                    samples[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                samples
            }
        }
    }

    fun release() {
        dismissAlarm()
        scope.coroutineContext.cancelChildren()
    }
}
