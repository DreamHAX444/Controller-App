package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAckEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes and deserializes CommandAckEnvelope with snake_case fields`() {
        val payload = """
            {
                "idempotency_key": "idemp-1234",
                "command_id": "cmd-5678",
                "status": "COMPLETED",
                "result_payload": "{\"success\":true}",
                "timestamp": 1700000000000
            }
        """.trimIndent()

        val envelope = json.decodeFromString<CommandAckEnvelope>(payload)
        assertEquals("idemp-1234", envelope.idempotencyKey)
        assertEquals("cmd-5678", envelope.commandId)
        assertEquals("COMPLETED", envelope.status)
        assertEquals("{\"success\":true}", envelope.resultPayload)
        assertEquals(1700000000000L, envelope.timestamp)
    }

    @Test
    fun `deserializes CommandAckEnvelope with camelCase fields`() {
        val payload = """
            {
                "idempotencyKey": "idemp-camel",
                "commandId": "cmd-camel",
                "status": "FAILED",
                "errorMessage": "Hardware timeout"
            }
        """.trimIndent()

        val envelope = json.decodeFromString<CommandAckEnvelope>(payload)
        assertEquals("idemp-camel", envelope.idempotencyKey)
        assertEquals("cmd-camel", envelope.commandId)
        assertEquals("FAILED", envelope.status)
        assertEquals("Hardware timeout", envelope.errorMessage)
        assertTrue(envelope.timestamp > 0)
    }
}
