package com.livetracker.controller.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.livetracker.controller.core.tracker.audio.data.AudioPlayer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AndroidAudioPlayer(
    private val sampleRate: Int = 8000
) : AudioPlayer {
    @Volatile
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(25)
    private var playbackThread: Thread? = null

    override fun start() {
        if (isPlaying.getAndSet(true)) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Ensure buffer has plenty of headroom (at least 16kB ~ 1 sec of 8kHz 16-bit mono)
        val bufferSize = maxOf(minBufferSize * 4, 16000)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        try {
            track.play()
        } catch (_: Exception) {}
        audioTrack = track

        playbackThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Exception) {}

            while (isPlaying.get()) {
                try {
                    val pcm = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val currentTrack = audioTrack ?: break
                    if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        currentTrack.write(pcm, 0, pcm.size)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }, "TraceAudioPlayback").apply {
            isDaemon = true
            start()
        }
    }

    override fun playPcm(pcm: ByteArray) {
        if (!isPlaying.get() || pcm.isEmpty()) return
        // Prevent buffer lag: if queue is getting backed up (>10 frames ~2.5s), drop the oldest
        while (audioQueue.size >= 10) {
            audioQueue.poll()
        }
        audioQueue.offer(pcm)
    }

    override fun setVolume(volume: Float) {
        try {
            val clamped = volume.coerceIn(0f, 1f)
            val track = audioTrack ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.setVolume(clamped)
            } else {
                @Suppress("DEPRECATION")
                track.setStereoVolume(clamped, clamped)
            }
        } catch (_: Exception) {}
    }

    override fun stop() {
        if (!isPlaying.getAndSet(false)) return

        audioQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

        val track = audioTrack
        audioTrack = null
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
    }
}
