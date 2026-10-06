package com.livetracker.controller.webrtc

import android.content.Context
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionFactory
import kotlinx.coroutines.CoroutineScope
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

class AndroidWebRtcSessionFactory(
    private val context: Context,
    private val scope: CoroutineScope
) : WebRtcSessionFactory {
    
    private val eglBaseContext: EglBase.Context by lazy {
        EglBase.create().eglBaseContext
    }
    
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    override fun createSession(
        trackerId: TrackerId,
        sessionId: String,
        configuration: WebRtcConfiguration
    ): WebRtcSession {
        return AndroidWebRtcSession(
            sessionId = sessionId,
            configuration = configuration,
            factory = factory,
            scope = scope
        )
    }
}
