package com.livetracker.controller.core.tracker.webrtc

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.signaling.SignalingMessage
import com.livetracker.controller.core.tracker.signaling.SignalingMessageType
import com.livetracker.controller.core.tracker.signaling.SignalingSession
import com.livetracker.controller.core.tracker.signaling.AnswerPayload
import com.livetracker.controller.core.tracker.signaling.IceCandidatePayload
import com.livetracker.controller.core.tracker.signaling.OfferPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Orchestrates the WebRTC Session and the Signaling Session for a specific Tracker.
 * Manages the exchange of SDPs and ICE candidates.
 */
class WebRtcSessionOrchestrator(
    private val trackerId: TrackerId,
    private val webRtcSession: WebRtcSession,
    private val signalingSession: SignalingSession,
    private val scope: CoroutineScope
) {
    private var signalObservationJob: Job? = null
    private var iceObservationJob: Job? = null

    fun start() {
        signalObservationJob?.cancel()
        iceObservationJob?.cancel()

        signalObservationJob = signalingSession.incomingMessages
            .onEach { message -> handleIncomingSignal(message) }
            .launchIn(scope)

        iceObservationJob = webRtcSession.localIceCandidates
            .onEach { candidate ->
                signalingSession.sendMessage(
                    type = SignalingMessageType.ICE_CANDIDATE,
                    payload = candidate
                )
            }
            .launchIn(scope)
    }

    private suspend fun handleIncomingSignal(message: SignalingMessage) {
        when (message.type) {
            SignalingMessageType.OFFER -> {
                val offer = message.payload as? OfferPayload ?: return
                try {
                    val answer = webRtcSession.setRemoteOfferAndCreateAnswer(offer)
                    signalingSession.sendMessage(
                        type = SignalingMessageType.ANSWER,
                        payload = answer
                    )
                } catch (e: Exception) {
                    // Log or handle error creating answer
                }
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = message.payload as? IceCandidatePayload ?: return
                webRtcSession.addIceCandidate(candidate)
            }
            SignalingMessageType.SESSION_ENDED, SignalingMessageType.STOP_REQUEST -> {
                stop()
            }
            else -> {
                // Handled elsewhere or ignored
            }
        }
    }

    fun stop() {
        signalObservationJob?.cancel()
        iceObservationJob?.cancel()
        scope.launch {
            webRtcSession.stop()
        }
    }
}
