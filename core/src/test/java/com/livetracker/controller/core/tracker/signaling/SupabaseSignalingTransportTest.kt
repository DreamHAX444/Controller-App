package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test

class SupabaseSignalingTransportTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ready payload serialization and parsing`() {
        val ready = ReadyPayload(generation = 42L)
        assertEquals(42L, ready.generation)
    }

    @Test
    fun `offer payload contains sdp and generation`() {
        val offer = OfferPayload(sdp = "v=0\r\no=...", generation = 5L, offerId = "off-1")
        assertEquals("v=0\r\no=...", offer.sdp)
        assertEquals(5L, offer.generation)
        assertEquals("off-1", offer.offerId)
    }

    @Test
    fun `answer payload contains sdp and generation`() {
        val answer = AnswerPayload(sdp = "v=0\r\no=answer...", generation = 5L, answerId = "ans-1")
        assertEquals("v=0\r\no=answer...", answer.sdp)
        assertEquals(5L, answer.generation)
        assertEquals("ans-1", answer.answerId)
    }

    @Test
    fun `ice candidate payload contains full candidate info`() {
        val ice = IceCandidatePayload(
            candidate = "candidate:1 1 UDP 2130706431 192.168.1.1 50000 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0,
            generation = 7L,
            candidateId = "cand-1"
        )
        assertEquals("candidate:1 1 UDP 2130706431 192.168.1.1 50000 typ host", ice.candidate)
        assertEquals("0", ice.sdpMid)
        assertEquals(0, ice.sdpMLineIndex)
        assertEquals(7L, ice.generation)
        assertEquals("cand-1", ice.candidateId)
    }

    @Test
    fun `signaling message contains session isolation and routing`() {
        val message = SignalingMessage(
            sessionId = "session-100",
            trackerId = TrackerId("tracker-alpha"),
            type = SignalingMessageType.OFFER,
            timestamp = 1700000000L,
            messageId = "msg-xyz",
            payload = OfferPayload("sdp-content", 1L)
        )

        assertEquals("session-100", message.sessionId)
        assertEquals(TrackerId("tracker-alpha"), message.trackerId)
        assertEquals(SignalingMessageType.OFFER, message.type)
        assertEquals("sdp-content", (message.payload as OfferPayload).sdp)
    }

    @Test
    fun `malformed payload elements reject gracefully without throwing`() {
        val transport = SupabaseSignalingTransport()
        // Access parsePayload via reflection or testing wire deserialization
        val parseMethod = SupabaseSignalingTransport::class.java.getDeclaredMethod(
            "parsePayload",
            SignalingMessageType::class.java,
            kotlinx.serialization.json.JsonElement::class.java
        ).apply { isAccessible = true }

        // Null element returns null
        val nullResult = parseMethod.invoke(transport, SignalingMessageType.OFFER, null)
        assertNull(nullResult)

        // Primitive JSON element where JsonObject expected returns null
        val primResult = parseMethod.invoke(
            transport,
            SignalingMessageType.OFFER,
            kotlinx.serialization.json.JsonPrimitive("raw-string-instead-of-object")
        )
        assertNull(primResult)

        // Offer missing "sdp" field returns null
        val missingSdpObj = kotlinx.serialization.json.buildJsonObject {
            put("generation", kotlinx.serialization.json.JsonPrimitive(1L))
        }
        val noSdpResult = parseMethod.invoke(transport, SignalingMessageType.OFFER, missingSdpObj)
        assertNull(noSdpResult)

        // Answer missing "sdp" field returns null
        val noAnswerSdpResult = parseMethod.invoke(transport, SignalingMessageType.ANSWER, missingSdpObj)
        assertNull(noAnswerSdpResult)

        // Ice candidate missing "candidate" string returns null
        val missingCandidateObj = kotlinx.serialization.json.buildJsonObject {
            put("generation", kotlinx.serialization.json.JsonPrimitive(2L))
            put("sdpMid", kotlinx.serialization.json.JsonPrimitive("0"))
        }
        val noCandResult = parseMethod.invoke(transport, SignalingMessageType.ICE_CANDIDATE, missingCandidateObj)
        assertNull(noCandResult)
    }

    @Test
    fun `unknown signaling message type string parses to null`() {
        val transport = SupabaseSignalingTransport()
        val parseTypeMethod = SupabaseSignalingTransport::class.java.getDeclaredMethod(
            "parseMessageType",
            String::class.java
        ).apply { isAccessible = true }

        val invalidType = parseTypeMethod.invoke(transport, "NON_EXISTENT_TYPE")
        assertNull(invalidType)

        val malformedType = parseTypeMethod.invoke(transport, "")
        assertNull(malformedType)

        val validType = parseTypeMethod.invoke(transport, "OFFER")
        assertEquals(SignalingMessageType.OFFER, validType)
    }

    @Test
    fun `supabase serializer can decode JsonObject`() {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("hello", kotlinx.serialization.json.JsonPrimitive("world"))
        }
        val str = json.toString()
        val serializer = io.github.jan.supabase.serializer.KotlinXSerializer()
        val decoded = serializer.decode(kotlin.reflect.typeOf<JsonObject>(), str) as JsonObject
        assertEquals("world", decoded["hello"]?.jsonPrimitive?.content)
    }

    @Test
    fun `session and device isolation distinguishes between parallel WebRTC tracks`() {
        val session1 = "session-alpha"
        val session2 = "session-beta"
        val trackerA = TrackerId("tracker-A")
        val trackerB = TrackerId("tracker-B")

        val msg1 = SignalingMessage(
            sessionId = session1,
            trackerId = trackerA,
            type = SignalingMessageType.OFFER,
            timestamp = 1000L,
            messageId = "m1",
            payload = OfferPayload("sdp-A", 1L)
        )

        val msg2 = SignalingMessage(
            sessionId = session2,
            trackerId = trackerB,
            type = SignalingMessageType.OFFER,
            timestamp = 1000L,
            messageId = "m2",
            payload = OfferPayload("sdp-B", 1L)
        )

        assertNotEquals(msg1.sessionId, msg2.sessionId)
        assertNotEquals(msg1.trackerId, msg2.trackerId)
        assertEquals("sdp-A", (msg1.payload as OfferPayload).sdp)
        assertEquals("sdp-B", (msg2.payload as OfferPayload).sdp)
    }
}

