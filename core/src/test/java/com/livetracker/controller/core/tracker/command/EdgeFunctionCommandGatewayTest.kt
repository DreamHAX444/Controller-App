package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.auth.data.StubCredentialStore
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.command.crypto.CommandCryptoUtil
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EdgeFunctionCommandGatewayTest {

    private val deviceId = TrackerId("tracker-alpha-01")
    private lateinit var credentialStore: StubCredentialStore
    private val fixedTime = 1776345600_000L

    @Before
    fun setUp() {
        credentialStore = StubCredentialStore()
    }

    private fun createSession(): Session {
        return Session(
            sessionId = "s1",
            user = User("u1", "Admin", Role.ADMIN, AccountStatus.ACTIVE, emptyList()),
            isAuthenticated = true,
            createdAt = fixedTime - 1000,
            expiresAt = fixedTime + 100_000
        )
    }

    private fun generateTestEcKeyPair(): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun signPreimage(keyPair: java.security.KeyPair, preimage: String): String {
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(preimage.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    @Test
    fun `successful command submission returns Accepted and transmits valid JWT authorization`() = runTest {
        credentialStore.storeSecureToken("valid-gotrue-jwt-token")

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 1042L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = """{"camera_id":"back"}""",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        var receivedUrl: String? = null
        var receivedHeaders: Map<String, String>? = null
        var receivedBody: String? = null

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { url, headers, body ->
                receivedUrl = url
                receivedHeaders = headers
                receivedBody = body

                GatewayHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "id": 1042,
                          "device_id": "tracker-alpha-01",
                          "command": "start_camera",
                          "params": {"camera_id": "back"},
                          "idempotency_key": "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234",
                          "status": "pending",
                          "key_id": "trace-srv-ecdsa-v1",
                          "server_signature": "$sigBase64",
                          "issued_at_epoch_sec": 1776345600,
                          "expires_at_epoch_sec": 1776345900
                        }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon-key-123",
            credentialStore = credentialStore,
            pinnedServerPublicKey = keyPair.public,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera(
            commandId = "client-cmd-1",
            deviceId = deviceId,
            timestamp = fixedTime,
            actorId = "u1",
            cameraId = "back"
        )

        val result = gateway.submitCommand(cmd, createSession(), "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234")

        assertTrue("Expected CommandResult.Accepted", result is CommandResult.Accepted)
        assertEquals("1042", (result as CommandResult.Accepted).commandId)
        assertEquals("https://example.supabase.co/functions/v1/commands/submit", receivedUrl)
        assertEquals("Bearer valid-gotrue-jwt-token", receivedHeaders?.get("Authorization"))
        assertEquals("anon-key-123", receivedHeaders?.get("apikey"))
        assertTrue(receivedBody!!.contains("\"device_id\":\"tracker-alpha-01\""))
        assertTrue(receivedBody!!.contains("\"command\":\"start_camera\""))
    }

    @Test
    fun `gateway 403 Forbidden returns CommandResult Unauthorized without retrying broadcast`() = runTest {
        credentialStore.storeSecureToken("visitor-jwt")

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(403, """{"error": "Forbidden", "message": "Permission denied"}""")
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StopCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession())

        assertTrue("Expected CommandResult.Unauthorized", result is CommandResult.Unauthorized)
    }

    @Test
    fun `gateway 404 or 503 Fails Closed and NEVER provides insecure broadcast fallback`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(404, "Gateway function not deployed")
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.RequestLocation("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession())

        assertTrue("Expected CommandResult.Failed", result is CommandResult.Failed)
        val failed = result as CommandResult.Failed
        assertTrue(failed.reason.contains("Secure command gateway unavailable"))
    }

    @Test
    fun `rfc 8785 canonical wire preimage matches Phase 0 reference test vector`() {
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 1042L,
            deviceId = "tracker-alpha-01",
            commandName = "take_photo",
            canonicalJsonParams = """{"camera":"back","flash":false,"quality":85}""",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345630L,
            idempotencyKey = "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234"
        )

        val sha256Hex = CommandCryptoUtil.computeSha256Hex(preimage)
        assertEquals(
            "157b1ac9f9792b7052e5d95c3ec8c8112797e193d43d40db2ffcede6b10599cb",
            sha256Hex
        )
    }

    @Test
    fun `server signature verification rejects forged responses`() = runTest {
        credentialStore.storeSecureToken("jwt")

        // Generate NIST P-256 KeyPairs
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val genuineKeyPair = kpg.generateKeyPair()
        val attackerKeyPair = kpg.generateKeyPair()

        // Attacker signs payload with unauthorized key
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(attackerKeyPair.private)
        sig.update("fake-preimage".toByteArray())
        val forgedSigDer = sig.sign()
        val forgedSigBase64 = Base64.getEncoder().encodeToString(forgedSigDer)

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 1042,
                      "device_id": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotency_key": "b3c0e12a",
                      "status": "pending",
                      "key_id": "key-1",
                      "server_signature": "$forgedSigBase64",
                      "issued_at_epoch_sec": 1776345600,
                      "expires_at_epoch_sec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedServerPublicKey = genuineKeyPair.public,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "b3c0e12a")

        assertTrue("Expected CommandResult.Failed due to signature failure", result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("signature verification failed"))
    }

    @Test
    fun `gateway rejects response when correlation fields mismatch request`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 1042,
                      "device_id": "tracker-alpha-01",
                      "command": "stop_camera",
                      "params": {},
                      "idempotency_key": "mismatched-key",
                      "status": "pending",
                      "key_id": "key-1",
                      "server_signature": "sig",
                      "issued_at_epoch_sec": 1776345600,
                      "expires_at_epoch_sec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "expected-key")

        assertTrue("Expected CommandResult.Failed due to correlation mismatch", result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("correlation mismatch"))
    }

    @Test
    fun `gateway rejects response when command timestamp is expired`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 1042,
                      "device_id": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotency_key": "test-key-123",
                      "status": "pending",
                      "key_id": "key-1",
                      "server_signature": "sig",
                      "issued_at_epoch_sec": 1776345000,
                      "expires_at_epoch_sec": 1776345500
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            timeProvider = { fixedTime } // 1776345600, past 1776345500
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "test-key-123")

        assertTrue("Expected CommandResult.Failed due to expired timestamp", result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("freshness check failed"))
    }

    @Test
    fun `gateway rejects command submission for Visitor role`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val visitorSession = Session(
            sessionId = "s-vis",
            user = User("u-vis", "Visitor", Role.VISITOR, AccountStatus.ACTIVE, emptyList()),
            isAuthenticated = true,
            createdAt = fixedTime - 1000,
            expiresAt = fixedTime + 100_000
        )

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ -> GatewayHttpResponse(200, "{}") },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u-vis")
        val result = gateway.submitCommand(cmd, visitorSession)

        assertTrue("Visitor session must be rejected with Unauthorized", result is CommandResult.Unauthorized)
    }

    @Test
    fun `camelCase wire response parsing handles Edge Function format seamlessly`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 1045L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "b3c0e12a-camel"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "id": 1045,
                          "deviceId": "tracker-alpha-01",
                          "command": "start_camera",
                          "params": {},
                          "idempotencyKey": "b3c0e12a-camel",
                          "status": "pending",
                          "keyId": "trace-srv-ecdsa-v1",
                          "serverSignature": "$sigBase64",
                          "issuedAtEpochSec": 1776345600,
                          "expiresAtEpochSec": 1776345900,
                          "createdAt": "2026-10-17T00:00:00Z"
                        }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedServerPublicKey = keyPair.public,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "b3c0e12a-camel")

        assertTrue("Expected CommandResult.Accepted", result is CommandResult.Accepted)
        assertEquals("1045", (result as CommandResult.Accepted).commandId)
    }

    @Test
    fun `pinnedKeyProvider resolves key by key_id and verifies signature`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 2001L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "key-prov-idemp"
        )
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(preimage.toByteArray(Charsets.UTF_8))
        val derSig = sig.sign()
        val sigBase64 = Base64.getEncoder().encodeToString(derSig)

        val keyProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider(
            mapOf("trace-srv-ecdsa-v1" to keyPair.public)
        )

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 2001,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "key-prov-idemp",
                      "status": "pending",
                      "keyId": "trace-srv-ecdsa-v1",
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
            pinnedKeyProvider = keyProvider,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "key-prov-idemp")

        assertTrue("Expected CommandResult.Accepted", result is CommandResult.Accepted)
        assertEquals("2001", (result as CommandResult.Accepted).commandId)
    }

    @Test
    fun `pinnedKeyProvider fails closed when key_id is unknown`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider(
            emptyMap() // No keys registered
        )

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 2002,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "unknown-key-idemp",
                      "status": "pending",
                      "keyId": "unregistered-key-id",
                      "serverSignature": "MEQCIA==",
                      "issuedAtEpochSec": 1776345600,
                      "expiresAtEpochSec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedKeyProvider = keyProvider,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "unknown-key-idemp")

        assertTrue("Expected CommandResult.Failed due to unknown key", result is CommandResult.Failed)
        assertTrue((result as CommandResult.Failed).reason.contains("Unknown signing key ID"))
    }

    @Test
    fun `submitting command with existing terminal status in commandStateStore returns terminal result without network call`() = runTest {
        credentialStore.storeSecureToken("jwt")

        var networkAttempted = false
        val stateStore = com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore()
        stateStore.recordCommand(
            com.livetracker.controller.core.tracker.command.persistence.TrackedCommand(
                idempotencyKey = "already-executed-key",
                commandId = 9999L,
                deviceId = deviceId.value,
                commandName = "start_camera",
                status = com.livetracker.controller.core.tracker.command.persistence.CommandStatus.EXECUTED,
                resultMessage = "Stream started successfully"
            )
        )

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                networkAttempted = true
                GatewayHttpResponse(200, "{}")
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            commandStateStore = stateStore,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "already-executed-key")

        assertFalse("Network request must NOT be sent for already terminal command", networkAttempted)
        assertTrue("Expected CommandResult.Completed", result is CommandResult.Completed)
        assertEquals("9999", (result as CommandResult.Completed).commandId)
        assertEquals("Stream started successfully", (result as CommandResult.Completed).payload)
    }

    @Test
    fun `gateway records PENDING then SUBMITTED transitions in commandStateStore`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 8888L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "store-transition-key"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        val stateStore = com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore()

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 8888,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "store-transition-key",
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

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val result = gateway.submitCommand(cmd, createSession(), "store-transition-key")

        assertTrue("Expected Accepted", result is CommandResult.Accepted)
        val stored = stateStore.getCommand("store-transition-key")
        assertNotNull(stored)
        assertEquals(8888L, stored?.commandId)
        assertEquals(com.livetracker.controller.core.tracker.command.persistence.CommandStatus.SUBMITTED, stored?.status)
    }

    @Test
    fun `concurrent submissions with identical idempotencyKey are coalesced into single network call`() = runTest {
        credentialStore.storeSecureToken("jwt")
        var postCount = 0

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 7777L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "coalesce-key-1"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                postCount++
                delay(50) // Simulate in-flight network latency
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 7777,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "coalesce-key-1",
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
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")

        val deferred1 = async { gateway.submitCommand(cmd, createSession(), "coalesce-key-1") }
        val deferred2 = async { gateway.submitCommand(cmd, createSession(), "coalesce-key-1") }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertTrue("Caller 1 should be Accepted", res1 is CommandResult.Accepted)
        assertTrue("Caller 2 should be Accepted", res2 is CommandResult.Accepted)
        assertEquals("7777", (res1 as CommandResult.Accepted).commandId)
        assertEquals("7777", (res2 as CommandResult.Accepted).commandId)
        assertEquals("Exactly one HTTP POST must be dispatched for concurrent identical keys", 1, postCount)
    }

    @Test
    fun `requireSignatureVerification fails closed when no trusted key is configured`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 6666,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "strict-key-1",
                      "status": "pending",
                      "keyId": "v1",
                      "serverSignature": "sig",
                      "issuedAtEpochSec": 1776345600,
                      "expiresAtEpochSec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedServerPublicKey = null,
            pinnedKeyProvider = null,
            requireSignatureVerification = true,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res = gateway.submitCommand(cmd, createSession(), "strict-key-1")

        assertTrue("Expected CommandResult.Failed when signature verification is required but no key configured", res is CommandResult.Failed)
        assertTrue((res as CommandResult.Failed).reason.contains("No trusted public key configured"))
    }

    @Test
    fun `response with blank keyId fails closed with signature verification error`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 3001,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "blank-keyid-test",
                      "status": "pending",
                      "keyId": "   ",
                      "serverSignature": "MEQCIA==",
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
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res = gateway.submitCommand(cmd, createSession(), "blank-keyid-test")

        assertTrue(res is CommandResult.Failed)
        assertTrue((res as CommandResult.Failed).reason.contains("missing keyId"))
    }

    @Test
    fun `response with blank serverSignature fails closed with signature verification error`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 3002,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "blank-sig-test",
                      "status": "pending",
                      "keyId": "v1",
                      "serverSignature": "",
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
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res = gateway.submitCommand(cmd, createSession(), "blank-sig-test")

        assertTrue(res is CommandResult.Failed)
        assertTrue((res as CommandResult.Failed).reason.contains("missing serverSignature"))
    }

    @Test
    fun `response with corrupt base64 signature fails closed`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 3003,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "corrupt-sig-test",
                      "status": "pending",
                      "keyId": "v1",
                      "serverSignature": "NOT_VALID_BASE64_!!!",
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
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res = gateway.submitCommand(cmd, createSession(), "corrupt-sig-test")

        assertTrue(res is CommandResult.Failed)
        assertTrue((res as CommandResult.Failed).reason.contains("malformed base64", ignoreCase = true))
    }

    @Test
    fun `initiator cancellation does not cancel concurrent callers awaiting coalesced response`() = runTest {
        credentialStore.storeSecureToken("jwt")

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 4001L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "cancel-coalesce-key"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        val networkStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val networkCanComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                networkStarted.complete(Unit)
                networkCanComplete.await()
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 4001,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "cancel-coalesce-key",
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
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")

        // Initiator coroutine
        val initiatorJob = launch {
            gateway.submitCommand(cmd, createSession(), "cancel-coalesce-key")
        }
        networkStarted.await()

        // Concurrent caller
        var concurrentResult: CommandResult? = null
        val concurrentJob = launch {
            concurrentResult = gateway.submitCommand(cmd, createSession(), "cancel-coalesce-key")
        }

        // Cancel initiator while in-flight
        initiatorJob.cancel()

        // Release network response
        networkCanComplete.complete(Unit)

        concurrentJob.join()

        // The concurrent caller MUST successfully receive Accepted even though initiator was cancelled
        assertNotNull(concurrentResult)
        assertTrue("Concurrent caller must receive Accepted", concurrentResult is CommandResult.Accepted)
        assertEquals("4001", (concurrentResult as CommandResult.Accepted).commandId)
    }

    @Test
    fun `missing trusted key configuration fails closed without permanently poisoning command as terminal FAILED`() = runTest {
        credentialStore.storeSecureToken("jwt")
        val stateStore = com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore()

        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 9001,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "unconfigured-key-1",
                      "status": "pending",
                      "keyId": "unknown-srv-key-v1",
                      "serverSignature": "MEQCIA==",
                      "issuedAtEpochSec": 1776345600,
                      "expiresAtEpochSec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedServerPublicKey = null,
            pinnedKeyProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider.EMPTY,
            commandStateStore = stateStore,
            requireSignatureVerification = true,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res = gateway.submitCommand(cmd, createSession(), "unconfigured-key-1")

        // 1. Must fail closed
        assertTrue("Must fail closed on unknown signing key", res is CommandResult.Failed)
        assertTrue((res as CommandResult.Failed).reason.contains("Unknown signing key"))

        // 2. Must NOT be terminal FAILED in store — preserved as PENDING with server sequence ID
        val tracked = stateStore.getCommand("unconfigured-key-1")
        assertNotNull("Command must be tracked in store", tracked)
        assertEquals(com.livetracker.controller.core.tracker.command.persistence.CommandStatus.PENDING, tracked?.status)
        assertEquals(9001L, tracked?.commandId)
        assertFalse("Command must NOT be in a terminal state", tracked!!.status.isTerminal())
    }

    @Test
    fun `command retried after trusted key becomes available succeeds and transitions to SUBMITTED`() = runTest {
        credentialStore.storeSecureToken("jwt")
        val stateStore = com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore()

        val keyPair = generateTestEcKeyPair()
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 9002L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "retry-after-key-provisioned"
        )
        val sigBase64 = signPreimage(keyPair, preimage)

        // Step 1: Initial submission with EMPTY key provider fails closed without poisoning
        val initialGateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 9002,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "retry-after-key-provisioned",
                      "status": "pending",
                      "keyId": "trusted-srv-v1",
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
            pinnedServerPublicKey = null,
            pinnedKeyProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider.EMPTY,
            commandStateStore = stateStore,
            requireSignatureVerification = true,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val initialRes = initialGateway.submitCommand(cmd, createSession(), "retry-after-key-provisioned")
        assertTrue("Initial submission must fail closed due to missing key in provider", initialRes is CommandResult.Failed)

        val trackedAfterFail = stateStore.getCommand("retry-after-key-provisioned")
        assertNotNull(trackedAfterFail)
        assertEquals(com.livetracker.controller.core.tracker.command.persistence.CommandStatus.PENDING, trackedAfterFail?.status)

        // Step 2: Key is now provisioned in key provider, retry same command identity
        val provisionedProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider(
            "trusted-srv-v1",
            keyPair.public
        )
        val provisionedGateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                // Server returns identical response (idempotent submission)
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 9002,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "retry-after-key-provisioned",
                      "status": "pending",
                      "keyId": "trusted-srv-v1",
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
            pinnedServerPublicKey = null,
            pinnedKeyProvider = provisionedProvider,
            commandStateStore = stateStore,
            requireSignatureVerification = true,
            timeProvider = { fixedTime }
        )

        val retryRes = provisionedGateway.submitCommand(cmd, createSession(), "retry-after-key-provisioned")

        // 3. Retry must succeed and return Accepted
        assertTrue("Retry must succeed once key is provisioned", retryRes is CommandResult.Accepted)
        assertEquals("9002", (retryRes as CommandResult.Accepted).commandId)

        // 4. Store must have transitioned to SUBMITTED
        val trackedAfterRetry = stateStore.getCommand("retry-after-key-provisioned")
        assertNotNull(trackedAfterRetry)
        assertEquals(com.livetracker.controller.core.tracker.command.persistence.CommandStatus.SUBMITTED, trackedAfterRetry?.status)
        assertEquals(9002L, trackedAfterRetry?.commandId)
    }

    @Test
    fun `genuine signature forgery marks command as terminal FAILED and blocks subsequent retries`() = runTest {
        credentialStore.storeSecureToken("jwt")
        val stateStore = com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore()

        val legitimateKeyPair = generateTestEcKeyPair()
        val attackerKeyPair = generateTestEcKeyPair()

        // Attacker signs with different/untrusted private key
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 9003L,
            deviceId = "tracker-alpha-01",
            commandName = "start_camera",
            canonicalJsonParams = "{}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "forged-sig-key-1"
        )
        val forgedSigBase64 = signPreimage(attackerKeyPair, preimage)

        var httpCallCount = 0
        val gateway = EdgeFunctionCommandGateway(
            httpPostSender = { _, _, _ ->
                httpCallCount++
                GatewayHttpResponse(
                    200,
                    """
                    {
                      "id": 9003,
                      "deviceId": "tracker-alpha-01",
                      "command": "start_camera",
                      "params": {},
                      "idempotencyKey": "forged-sig-key-1",
                      "status": "pending",
                      "keyId": "srv-key-v1",
                      "serverSignature": "$forgedSigBase64",
                      "issuedAtEpochSec": 1776345600,
                      "expiresAtEpochSec": 1776345900
                    }
                    """.trimIndent()
                )
            },
            baseUrl = "https://example.supabase.co",
            anonKey = "anon",
            credentialStore = credentialStore,
            pinnedKeyProvider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider("srv-key-v1", legitimateKeyPair.public),
            commandStateStore = stateStore,
            requireSignatureVerification = true,
            timeProvider = { fixedTime }
        )

        val cmd = TrackerCommand.StartCamera("c1", deviceId, fixedTime, "u1")
        val res1 = gateway.submitCommand(cmd, createSession(), "forged-sig-key-1")

        assertTrue("Forged signature must fail", res1 is CommandResult.Failed)
        assertTrue((res1 as CommandResult.Failed).reason.contains("Corrupt or forged"))
        assertEquals(1, httpCallCount)

        // Store must be marked terminal FAILED
        val tracked = stateStore.getCommand("forged-sig-key-1")
        assertNotNull(tracked)
        assertEquals(com.livetracker.controller.core.tracker.command.persistence.CommandStatus.FAILED, tracked?.status)
        assertTrue(tracked!!.status.isTerminal())

        // Attempting to retry the forged command must immediately fail at terminal guard with zero network calls
        val res2 = gateway.submitCommand(cmd, createSession(), "forged-sig-key-1")
        assertTrue("Subsequent retry of terminal failed command must fail immediately", res2 is CommandResult.Failed)
        assertEquals("No second network call should occur for terminal command", 1, httpCallCount)
    }
}
