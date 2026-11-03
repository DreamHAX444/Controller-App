package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.auth.data.StubCredentialStore
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.command.persistence.CommandStatus
import com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CommandConcurrencyAndStressTest {

    private val deviceId = TrackerId("tracker-stress-01")
    private lateinit var credentialStore: StubCredentialStore
    private val fixedTime = 1776345600_000L

    @Before
    fun setUp() {
        credentialStore = StubCredentialStore()
        kotlinx.coroutines.runBlocking {
            credentialStore.storeSecureToken("stress-test-jwt")
        }
    }

    private fun createSession(isValid: Boolean = true): Session {
        return Session(
            sessionId = "sess-stress",
            user = User("u-stress", "Stress User", Role.ADMIN, AccountStatus.ACTIVE, emptyList()),
            isAuthenticated = isValid,
            createdAt = fixedTime - 10_000,
            expiresAt = if (isValid) fixedTime + 100_000 else fixedTime - 1000
        )
    }

    @Test
    fun `concurrent duplicate submissions with identical idempotencyKey resolve safely`() = runTest {
        val stateStore = InMemoryCommandStateStore()
        val networkCallCount = AtomicInteger(0)

        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val preimage = com.livetracker.controller.core.tracker.command.crypto.CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 7701L,
            deviceId = "tracker-stress-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "concurrent-key-1"
        )
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(preimage.toByteArray(Charsets.UTF_8))
        val sigBase64 = java.util.Base64.getEncoder().encodeToString(sig.sign())

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                networkCallCount.incrementAndGet()
                GatewayHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "id": 7701,
                          "deviceId": "tracker-stress-01",
                          "command": "start_camera",
                          "params": {},
                          "idempotencyKey": "concurrent-key-1",
                          "status": "pending",
                          "keyId": "v1",
                          "serverSignature": "$sigBase64",
                          "issuedAtEpochSec": 1776345600,
                          "expiresAtEpochSec": 1776345900
                        }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedServerPublicKey = keyPair.public,
            commandStateStore = stateStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u-stress")

        // Launch 10 concurrent submissions with identical idempotency key
        val deferreds = (1..10).map {
            async {
                gateway.submitCommand(cmd, createSession(), "concurrent-key-1")
            }
        }
        val results = deferreds.awaitAll()

        // All should succeed (Accepted or Completed if cached)
        results.forEach { result ->
            assertTrue("Expected Accepted or Completed, got $result", result is CommandResult.Accepted || result is CommandResult.Completed)
        }

        // Verify state in store is SUBMITTED with server sequence id 7701
        val stored = stateStore.getCommand("concurrent-key-1")
        assertNotNull(stored)
        assertEquals(7701L, stored?.commandId)
        assertEquals(CommandStatus.SUBMITTED, stored?.status)
    }

    @Test
    fun `malformed JSON server response fails closed and records error without throwing`() = runTest {
        val stateStore = InMemoryCommandStateStore()

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    statusCode = 200,
                    body = "<html><body>502 Bad Gateway - HTML error from upstream CDN</body></html>"
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            commandStateStore = stateStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u-stress")
        val result = gateway.submitCommand(cmd, createSession(), "malformed-json-key")

        assertTrue("Expected Failed result on malformed response", result is CommandResult.Failed)
        val failed = result as CommandResult.Failed
        assertTrue(failed.reason.contains("Secure command gateway unavailable"))
    }

    @Test
    fun `session expiry between retry attempts fails closed with Unauthorized`() = runTest {
        val stateStore = InMemoryCommandStateStore()

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(504, "Gateway Timeout")
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            commandStateStore = stateStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u-stress")

        // First attempt with valid session times out (504)
        val result1 = gateway.submitCommand(cmd, createSession(isValid = true), "expired-retry-key")
        assertTrue(result1 is CommandResult.Failed)

        // Retry attempt with expired session fails immediately with Unauthorized
        val result2 = gateway.submitCommand(cmd, createSession(isValid = false), "expired-retry-key")
        assertTrue("Expected Unauthorized on expired session retry", result2 is CommandResult.Unauthorized)
    }

    @Test
    fun `delayed execution after initial timeout updates state store to EXECUTED`() = runTest {
        val stateStore = InMemoryCommandStateStore()

        // Simulate initial command registered and submitted
        val initialCommand = com.livetracker.controller.core.tracker.command.persistence.TrackedCommand(
            idempotencyKey = "delayed-ack-key",
            commandId = 5500L,
            deviceId = deviceId.value,
            commandName = "start_camera",
            status = CommandStatus.SUBMITTED,
            createdAtEpochMs = fixedTime
        )
        stateStore.recordCommand(initialCommand)

        // Delayed Realtime execution envelope arrives
        val envelope = com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerCommandEnvelope(
            id = 5500L,
            deviceId = deviceId.value,
            command = "start_camera",
            params = "Stream initialized at 30fps",
            status = "executed",
            idempotencyKey = "delayed-ack-key"
        )

        val updateResult = stateStore.updateStatus("delayed-ack-key", CommandStatus.EXECUTED, envelope.getParamsAsString())
        assertTrue(updateResult.isSuccess)

        val updated = stateStore.getCommand("delayed-ack-key")
        assertEquals(CommandStatus.EXECUTED, updated?.status)
        assertEquals("Stream initialized at 30fps", updated?.resultMessage)
        assertEquals(5500L, updated?.commandId)
    }
}
