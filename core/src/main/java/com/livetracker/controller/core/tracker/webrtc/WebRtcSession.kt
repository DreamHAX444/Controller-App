package com.livetracker.controller.core.tracker.webrtc

import com.livetracker.controller.core.tracker.signaling.AnswerPayload
import com.livetracker.controller.core.tracker.signaling.IceCandidatePayload
import com.livetracker.controller.core.tracker.signaling.OfferPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller-side abstraction for a WebRTC session.
 * Hides org.webrtc implementation details from the domain layer.
 */
interface WebRtcSession {
    /** The current state of the WebRTC connection. */
    val state: StateFlow<WebRtcState>
    
    /** Emits ICE candidates generated locally to be sent to the Tracker. */
    val localIceCandidates: Flow<IceCandidatePayload>
    
    /** 
     * Applies the remote offer from the Tracker and generates a local answer. 
     */
    suspend fun setRemoteOfferAndCreateAnswer(offer: OfferPayload): AnswerPayload
    
    /** 
     * Applies an ICE candidate received from the Tracker. 
     */
    suspend fun addIceCandidate(candidate: IceCandidatePayload)
    
    /** 
     * Stops the WebRTC session and releases associated resources. 
     */
    suspend fun stop()
    
    /** Creates a new DataChannel with the given label */
    fun createDataChannel(label: String): com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel?
    
    /** Emits DataChannels created by the remote peer */
    val incomingDataChannels: Flow<com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel>
}
