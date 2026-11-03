package com.livetracker.controller.core.tracker.domain

import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerCommandEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TrackerCommandProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = TrackerId("tracker-007")

    @Test
    fun `TrackerCommand preserves commandId, assigns idempotencyKey, and parses sequenceId`() {
        // When constructed with numeric string
        val cmdNumeric = TrackerCommand.StartCamera(
            commandId = "1042",
            deviceId = deviceId,
            timestamp = 1000L,
            actorId = "u1"
        )
        assertEquals("1042", cmdNumeric.commandId)
        assertEquals("1042", cmdNumeric.idempotencyKey)
        assertEquals(1042L, cmdNumeric.sequenceId)

        // When constructed with UUID string
        val uuid = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
        val cmdUuid = TrackerCommand.RequestLocation(
            commandId = uuid,
            deviceId = deviceId,
            timestamp = 1000L,
            actorId = "u1"
        )
        assertEquals(uuid, cmdUuid.commandId)
        assertEquals(uuid, cmdUuid.idempotencyKey)
        assertNull("Non-numeric UUID must yield null sequenceId", cmdUuid.sequenceId)
    }

    @Test
    fun `TrackerCommand default construction generates unique idempotency keys`() {
        val cmd1 = TrackerCommand.StopCamera(deviceId = deviceId)
        val cmd2 = TrackerCommand.StopCamera(deviceId = deviceId)

        assertNotNull(cmd1.commandId)
        assertNotNull(cmd2.commandId)
        assertNotEquals(cmd1.idempotencyKey, cmd2.idempotencyKey)
    }

    @Test
    fun `TrackerCommandEnvelope serializes and deserializes dual identity fields`() {
        val envelope = TrackerCommandEnvelope(
            id = 1042L,
            deviceId = "tracker-007",
            command = "start_camera",
            params = """{"quality":"high"}""",
            idempotencyKey = "uuid-key-999"
        )

        val serialized = json.encodeToString(TrackerCommandEnvelope.serializer(), envelope)
        assertTrue(serialized.contains("\"id\":1042"))
        assertTrue(serialized.contains("\"idempotency_key\":\"uuid-key-999\""))
        assertTrue(serialized.contains("\"device_id\":\"tracker-007\""))

        val deserialized = json.decodeFromString(TrackerCommandEnvelope.serializer(), serialized)
        assertEquals(1042L, deserialized.id)
        assertEquals("uuid-key-999", deserialized.idempotencyKey)
        assertEquals("tracker-007", deserialized.deviceId)
        assertEquals("1042", deserialized.effectiveCorrelationId)
    }

    @Test
    fun `TrackerCommandEnvelope effectiveCorrelationId falls back to idempotencyKey when id is null`() {
        val envelope = TrackerCommandEnvelope(
            id = null,
            deviceId = "tracker-007",
            command = "request_location",
            idempotencyKey = "uuid-direct-flow"
        )

        assertEquals("uuid-direct-flow", envelope.effectiveCorrelationId)
    }
}
