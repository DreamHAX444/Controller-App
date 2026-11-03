package com.livetracker.controller.webrtc

import com.livetracker.controller.core.tracker.webrtc.IceServer
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

class AndroidWebRtcSessionConfigTest {

    @Test
    fun buildRtcConfiguration_appliesPhase4CellularResiliencePolicies() {
        val config = WebRtcConfiguration()
        val rtcConfig = AndroidWebRtcSession.buildRtcConfiguration(config)

        assertNotNull(rtcConfig)
        assertEquals(PeerConnection.SdpSemantics.UNIFIED_PLAN, rtcConfig.sdpSemantics)
        assertEquals(PeerConnection.BundlePolicy.MAXBUNDLE, rtcConfig.bundlePolicy)
        assertEquals(PeerConnection.RtcpMuxPolicy.REQUIRE, rtcConfig.rtcpMuxPolicy)
        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY, rtcConfig.continualGatheringPolicy)
        assertEquals(PeerConnection.CandidateNetworkPolicy.ALL, rtcConfig.candidateNetworkPolicy)
        assertEquals(PeerConnection.TcpCandidatePolicy.ENABLED, rtcConfig.tcpCandidatePolicy)
        assertEquals(PeerConnection.IceTransportsType.ALL, rtcConfig.iceTransportsType)
        assertEquals(2, rtcConfig.iceCandidatePoolSize)

        // Verifies all 6 multi-STUN servers are mapped into rtcConfig.iceServers
        assertEquals(6, rtcConfig.iceServers.size)
        val urls = rtcConfig.iceServers.flatMap { it.urls }
        assertTrue(urls.contains("stun:stun.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun.cloudflare.com:3478"))
    }

    @Test
    fun buildRtcConfiguration_withTurnServers_mapsCredentialsCorrectly() {
        val turnUrl = "turn:turn.example.com:3478?transport=udp"
        val config = WebRtcConfiguration.createWithTurn(
            turnUrls = listOf(turnUrl),
            username = "turn_user",
            credential = "turn_password_secret",
            includeDefaultStun = false,
            iceCandidatePoolSize = 4
        )

        val rtcConfig = AndroidWebRtcSession.buildRtcConfiguration(config)

        assertEquals(1, rtcConfig.iceServers.size)
        assertEquals(4, rtcConfig.iceCandidatePoolSize)

        val turnServer = rtcConfig.iceServers[0]
        assertEquals("turn_user", turnServer.username)
        assertEquals("turn_password_secret", turnServer.password)
        assertTrue(turnServer.urls.contains(turnUrl))
    }

    @Test
    fun buildRtcConfiguration_customGatheringAndTcpDisabled_honorsFlags() {
        val config = WebRtcConfiguration(
            iceServers = listOf(IceServer("stun:custom.stun.com:3478")),
            iceCandidatePoolSize = 0,
            continualGathering = false,
            tcpCandidatesEnabled = false
        )

        val rtcConfig = AndroidWebRtcSession.buildRtcConfiguration(config)

        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_ONCE, rtcConfig.continualGatheringPolicy)
        assertEquals(PeerConnection.TcpCandidatePolicy.DISABLED, rtcConfig.tcpCandidatePolicy)
        assertEquals(0, rtcConfig.iceCandidatePoolSize)
    }
}
