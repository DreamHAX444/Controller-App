package com.livetracker.controller.core.tracker.webrtc

/**
 * Represents an ICE server (STUN or TURN) configuration for WebRTC peer connections.
 */
data class IceServer(
    val url: String,
    val username: String? = null,
    val password: String? = null
)

/**
 * Phase 4 WebRTC configuration optimized for cellular carrier network resilience,
 * symmetric NAT traversal, and ultra-low-latency ICE candidate discovery.
 */
data class WebRtcConfiguration(
    val iceServers: List<IceServer> = DEFAULT_ICE_SERVERS,
    val iceCandidatePoolSize: Int = DEFAULT_ICE_CANDIDATE_POOL_SIZE,
    val continualGathering: Boolean = true,
    val tcpCandidatesEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_ICE_CANDIDATE_POOL_SIZE = 2

        /**
         * Redundant multi-STUN pool across Google and Cloudflare Anycast endpoints.
         */
        val DEFAULT_STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun3.l.google.com:19302",
            "stun:stun4.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )

        val DEFAULT_ICE_SERVERS: List<IceServer> = DEFAULT_STUN_SERVERS.map { IceServer(url = it) }

        /**
         * Factory function to create a configuration enriched with TURN relay credentials
         * for guaranteed traversal across Carrier-Grade NAT (CGNAT) / symmetric cellular networks.
         */
        fun createWithTurn(
            turnUrls: List<String>,
            username: String,
            credential: String,
            includeDefaultStun: Boolean = true,
            iceCandidatePoolSize: Int = DEFAULT_ICE_CANDIDATE_POOL_SIZE
        ): WebRtcConfiguration {
            val servers = mutableListOf<IceServer>()
            if (includeDefaultStun) {
                servers.addAll(DEFAULT_ICE_SERVERS)
            }
            turnUrls.forEach { url ->
                servers.add(IceServer(url = url, username = username, password = credential))
            }
            return WebRtcConfiguration(
                iceServers = servers,
                iceCandidatePoolSize = iceCandidatePoolSize
            )
        }
    }
}
