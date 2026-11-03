package com.livetracker.controller.core.tracker.signaling

import android.util.Log
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class WireSignalMessage(
    val sessionId: String,
    val deviceId: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String = java.util.UUID.randomUUID().toString(),
    val payload: JsonElement? = null
)

/**
 * Concrete [SignalingTransport] powered by Supabase Realtime WebSocket broadcast channels.
 * Uses dedicated per-session channel `webrtc-{sessionId}` matching tracker_app's SupabaseWebRtcSignalingTransport.
 */
class SupabaseSignalingTransport(
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.client }
) : SignalingTransport {

    private val tag = "SupabaseSignalingTransport"
    private val channels = ConcurrentHashMap<String, RealtimeChannel>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getChannelTopic(sessionId: String): String = "webrtc-$sessionId"

    private suspend fun getOrCreateChannel(client: SupabaseClient, sessionId: String): RealtimeChannel {
        channels[sessionId]?.let { return it }
        val topic = getChannelTopic(sessionId)
        client.realtime.subscriptions.values.find { it.topic == topic || it.topic == "realtime:$topic" }?.let { stale ->
            try { stale.unsubscribe() } catch (_: Exception) {}
            try { client.realtime.removeChannel(stale) } catch (_: Exception) {}
        }
        val channel = client.channel(topic)
        channels[sessionId] = channel
        return channel
    }

    override suspend fun connect(sessionId: String) {
        mutex.withLock {
            val client = clientProvider()
            val topic = getChannelTopic(sessionId)
            val channel = getOrCreateChannel(client, sessionId)
            if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
                withTimeout(10_000L) {
                    channel.subscribe(blockUntilSubscribed = true)
                }
            }
            Log.d(tag, "Connected signaling on dedicated channel: $topic (session=$sessionId)")
        }
    }

    override suspend fun disconnect(sessionId: String) {
        mutex.withLock {
            channels.remove(sessionId)?.let { channel ->
                try {
                    channel.unsubscribe()
                } catch (_: Exception) {}
                try {
                    clientProvider().realtime.removeChannel(channel)
                } catch (_: Exception) {}
            }
            Log.d(tag, "Disconnected signaling session: $sessionId")
        }
    }

    override fun receiveMessages(sessionId: String): Flow<SignalingMessage> {
        val client = clientProvider()
        val channel = channels[sessionId] ?: run {
            val topic = getChannelTopic(sessionId)
            val ch = client.realtime.subscriptions.values.find { it.topic == topic || it.topic == "realtime:$topic" }
                ?: client.channel(topic)
            channels[sessionId] = ch
            ch
        }

        return channel.broadcastFlow<JsonObject>(event = "signal")
            .mapNotNull { jsonObject ->
                try {
                    val wire = json.decodeFromJsonElement<WireSignalMessage>(jsonObject)
                    Log.d(tag, "Decoded signal wire envelope: type=${wire.type}, sender=${wire.deviceId}, wireSession=${wire.sessionId}, targetSession=$sessionId")
                    wire
                } catch (e: Exception) {
                    Log.w(tag, "Failed to decode signal wire message: ${e.message}")
                    null
                }
            }
            .filter { wire ->
                val cleanWireSession = extractSessionId(wire.sessionId)
                cleanWireSession == sessionId || wire.sessionId == sessionId
            }
            .mapNotNull { wire ->
                val messageType = parseMessageType(wire.type) ?: run {
                    Log.w(tag, "Unknown wire signal type: ${wire.type}")
                    return@mapNotNull null
                }
                val payload = parsePayload(messageType, wire.payload)

                Log.d(tag, "Received matching signal wire envelope: type=${wire.type}, sender=${wire.deviceId}, session=${wire.sessionId}")

                SignalingMessage(
                    sessionId = sessionId,
                    trackerId = TrackerId(wire.deviceId),
                    type = messageType,
                    timestamp = wire.timestamp,
                    messageId = wire.messageId,
                    payload = payload
                )
            }
    }

    override suspend fun sendMessage(message: SignalingMessage) {
        val client = clientProvider()
        val channel = channels[message.sessionId] ?: getOrCreateChannel(client, message.sessionId)
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
            withTimeout(10_000L) {
                channel.subscribe(blockUntilSubscribed = true)
            }
        }

        val wire = WireSignalMessage(
            sessionId = message.sessionId,
            deviceId = "controller",
            type = message.type.name,
            timestamp = message.timestamp,
            messageId = message.messageId,
            payload = encodePayload(message.payload)
        )

        val jsonObject = json.encodeToJsonElement(wire) as JsonObject
        channel.broadcast(event = "signal", message = jsonObject)
        Log.d(tag, "Dispatched signaling message: ${message.type} (sessionId=${message.sessionId}) on topic=${channel.topic}")
    }

    private fun parseMessageType(typeStr: String): SignalingMessageType? {
        return try {
            SignalingMessageType.valueOf(typeStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSessionId(raw: String): String {
        if (!raw.contains("{") && !raw.contains("\"")) return raw.trim()
        return try {
            val jsonElem = json.parseToJsonElement(raw)
            if (jsonElem is JsonObject) {
                val sElem = jsonElem["sessionId"] ?: jsonElem["session_id"]
                when {
                    sElem is JsonPrimitive && sElem.isString -> sElem.content
                    sElem != null -> sElem.toString().trim('"')
                    else -> raw.trim()
                }
            } else {
                raw.trim()
            }
        } catch (_: Exception) {
            raw.trim()
        }
    }

    private fun parsePayload(type: SignalingMessageType, element: JsonElement?): SignalingPayload? {
        if (element !is JsonObject) return null
        return when (type) {
            SignalingMessageType.READY -> {
                val gen = element["generation"]?.jsonPrimitive?.longOrNull ?: 0L
                ReadyPayload(generation = gen)
            }
            SignalingMessageType.OFFER -> {
                val sdp = element["sdp"]?.jsonPrimitive?.contentOrNull ?: return null
                val gen = element["generation"]?.jsonPrimitive?.longOrNull ?: 0L
                val offerId = element["offerId"]?.jsonPrimitive?.contentOrNull ?: ""
                OfferPayload(sdp = sdp, generation = gen, offerId = offerId)
            }
            SignalingMessageType.ANSWER -> {
                val sdp = element["sdp"]?.jsonPrimitive?.contentOrNull ?: return null
                val gen = element["generation"]?.jsonPrimitive?.longOrNull ?: 0L
                val answerId = element["answerId"]?.jsonPrimitive?.contentOrNull ?: ""
                AnswerPayload(sdp = sdp, generation = gen, answerId = answerId)
            }
            SignalingMessageType.ICE_CANDIDATE -> {
                val candidate = element["candidate"]?.jsonPrimitive?.contentOrNull ?: return null
                val sdpMid = element["sdpMid"]?.jsonPrimitive?.contentOrNull ?: ""
                val sdpMLineIndex = element["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val gen = element["generation"]?.jsonPrimitive?.longOrNull ?: 0L
                val candidateId = element["candidateId"]?.jsonPrimitive?.contentOrNull ?: ""
                IceCandidatePayload(
                    candidate = candidate,
                    sdpMid = sdpMid,
                    sdpMLineIndex = sdpMLineIndex,
                    generation = gen,
                    candidateId = candidateId
                )
            }
            SignalingMessageType.ERROR -> {
                val message = element["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                ErrorPayload(message = message)
            }
            else -> null
        }
    }

    private fun encodePayload(payload: SignalingPayload?): JsonElement? {
        return when (payload) {
            is ReadyPayload -> buildJsonObject {
                put("generation", payload.generation)
            }
            is OfferPayload -> buildJsonObject {
                put("sdp", payload.sdp)
                put("generation", payload.generation)
                put("offerId", payload.offerId)
            }
            is AnswerPayload -> buildJsonObject {
                put("sdp", payload.sdp)
                put("generation", payload.generation)
                put("answerId", payload.answerId)
            }
            is IceCandidatePayload -> buildJsonObject {
                put("candidate", payload.candidate)
                put("sdpMid", payload.sdpMid)
                put("sdpMLineIndex", payload.sdpMLineIndex)
                put("generation", payload.generation)
                put("candidateId", payload.candidateId)
            }
            is ErrorPayload -> buildJsonObject {
                put("message", payload.message)
            }
            null -> null
        }
    }
}
