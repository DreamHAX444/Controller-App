package com.example.livelocationservice.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

object WebRtcPeerConnectionFactory {
    private var isInitialized = false
    private var factory: PeerConnectionFactory? = null
    val eglBase: EglBase by lazy { EglBase.create() }

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        isInitialized = true
    }

    @Synchronized
    fun getFactory(adm: org.webrtc.audio.AudioDeviceModule? = null): PeerConnectionFactory {
        if (factory != null && adm == null) return factory!!
        
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(
            eglBase.eglBaseContext
        )

        val builder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
        
        if (adm != null) {
            builder.setAudioDeviceModule(adm)
        }

        val newFactory = builder.createPeerConnectionFactory()
        
        if (adm == null) {
            factory = newFactory
        }
        
        return newFactory
    }
}
