package com.livetracker.controller.core.tracker.webrtc

import android.util.Log
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
    private val tag = "WebRtcSessionOrchestrator"
    private var signalObservationJob: Job? = null
    private var iceObservationJob: Job? = null

    fun start() {
        signalObservationJob?.cancel()
        iceObservationJob?.cancel()

        scope.launch {
            try {
                Log.d(tag, "Starting signaling session for tracker: $trackerId")
                signalingSession.start()
                Log.d(tag, "Signaling session connected for tracker: $trackerId")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start signaling session: ${e.message}", e)
            }
        }

        signalObservationJob = signalingSession.incomingMessages
            .onEach { message -> handleIncomingSignal(message) }
            .launchIn(scope)

        iceObservationJob = webRtcSession.localIceCandidates
            .onEach { candidate ->
                try {
                    signalingSession.sendMessage(
                        type = SignalingMessageType.ICE_CANDIDATE,
                        payload = candidate
                    )
                    Log.d(tag, "Sent local ICE candidate to $trackerId")
                } catch (e: Exception) {
                    Log.w(tag, "Failed to send local ICE candidate: ${e.message}")
                }
            }
            .launchIn(scope)
    }

    private suspend fun handleIncomingSignal(message: SignalingMessage) {
        Log.d(tag, "Incoming signal: ${message.type} from ${message.trackerId}")
        when (message.type) {
            SignalingMessageType.OFFER -> {
                val offer = message.payload as? OfferPayload ?: run {
                    Log.w(tag, "Received OFFER with invalid payload: ${message.payload}")
                    return
                }
                try {
                    Log.i(tag, "Processing remote OFFER from $trackerId (gen=${offer.generation})")
                    val answer = webRtcSession.setRemoteOfferAndCreateAnswer(offer)
                    signalingSession.sendMessage(
                        type = SignalingMessageType.ANSWER,
                        payload = answer
                    )
                    Log.i(tag, "Generated and dispatched WebRTC ANSWER to $trackerId")
                } catch (e: Exception) {
                    Log.e(tag, "Failed to process OFFER / create ANSWER: ${e.message}", e)
                }
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = message.payload as? IceCandidatePayload ?: return
                try {
                    webRtcSession.addIceCandidate(candidate)
                    Log.d(tag, "Applied remote ICE candidate from $trackerId")
                } catch (e: Exception) {
                    Log.w(tag, "Failed to apply remote ICE candidate: ${e.message}")
                }
            }
            SignalingMessageType.SESSION_ENDED, SignalingMessageType.STOP_REQUEST -> {
                Log.i(tag, "Session ended signal received from $trackerId")
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
            try {
                signalingSession.stop()
            } catch (e: Exception) {
                Log.w(tag, "Error stopping signaling session: ${e.message}")
            }
            try {
                webRtcSession.stop()
            } catch (e: Exception) {
                Log.w(tag, "Error stopping webRtc session: ${e.message}")
            }
        }
    }
}
