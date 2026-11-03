package com.livetracker.controller.core.tracker.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcConfigurationTest {

    @Test
    fun defaultConfiguration_hasRedundantMultiStunEndpoints() {
        val config = WebRtcConfiguration()

        assertEquals(6, config.iceServers.size)
        assertEquals(2, config.iceCandidatePoolSize)
        assertTrue(config.continualGathering)
        assertTrue(config.tcpCandidatesEnabled)

        val urls = config.iceServers.map { it.url }
        assertTrue(urls.contains("stun:stun.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun1.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun2.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun3.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun4.l.google.com:19302"))
        assertTrue(urls.contains("stun:stun.cloudflare.com:3478"))

        // Default STUN servers do not require username/password
        config.iceServers.forEach { server ->
            assertNull(server.username)
            assertNull(server.password)
        }
    }

    @Test
    fun createWithTurn_includesStunAndTurnServers() {
        val turnUrls = listOf(
            "turn:turn.example.com:3478?transport=udp",
            "turn:turn.example.com:3478?transport=tcp",
            "turns:turn.example.com:443?transport=tcp"
        )
        val username = "testuser"
        val credential = "testpassword123"

        val config = WebRtcConfiguration.createWithTurn(
            turnUrls = turnUrls,
            username = username,
            credential = credential,
            includeDefaultStun = true,
            iceCandidatePoolSize = 3
        )

        // 6 default STUN + 3 TURN servers = 9 total
        assertEquals(9, config.iceServers.size)
        assertEquals(3, config.iceCandidatePoolSize)

        // First 6 are STUN
        for (i in 0 until 6) {
            assertTrue(config.iceServers[i].url.startsWith("stun:"))
            assertNull(config.iceServers[i].username)
        }

        // Last 3 are TURN with credentials
        val turnServers = config.iceServers.subList(6, 9)
        assertEquals(turnUrls[0], turnServers[0].url)
        assertEquals(username, turnServers[0].username)
        assertEquals(credential, turnServers[0].password)

        assertEquals(turnUrls[1], turnServers[1].url)
        assertEquals(turnUrls[2], turnServers[2].url)
    }

    @Test
    fun createWithTurn_turnOnlyWhenDefaultStunExcluded() {
        val turnUrls = listOf("turn:turn.example.com:3478?transport=udp")
        val config = WebRtcConfiguration.createWithTurn(
            turnUrls = turnUrls,
            username = "u",
            credential = "p",
            includeDefaultStun = false
        )

        assertEquals(1, config.iceServers.size)
        assertEquals("turn:turn.example.com:3478?transport=udp", config.iceServers[0].url)
        assertEquals("u", config.iceServers[0].username)
        assertEquals("p", config.iceServers[0].password)
    }

    @Test
    fun customOptions_honored() {
        val config = WebRtcConfiguration(
            iceServers = listOf(IceServer("stun:custom.stun.org:3478")),
            iceCandidatePoolSize = 0,
            continualGathering = false,
            tcpCandidatesEnabled = false
        )

        assertEquals(1, config.iceServers.size)
        assertEquals(0, config.iceCandidatePoolSize)
        assertFalse(config.continualGathering)
        assertFalse(config.tcpCandidatesEnabled)
    }
}
