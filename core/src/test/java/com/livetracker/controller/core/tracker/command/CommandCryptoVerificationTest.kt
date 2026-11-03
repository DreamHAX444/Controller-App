package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.tracker.command.crypto.CommandCryptoUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tier 2 Cryptographic Protocol Verification Tests.
 *
 * Verifies RFC 8785 canonical wire preimage digests, NIST ECDSA P-256 signature verification,
 * IEEE P1363 to ASN.1/DER encoding bridge, and expiration validation via [CommandCryptoUtil].
 */
class CommandCryptoVerificationTest {

    companion object {
        private const val SPEC_TEST_VECTOR_PREIMAGE =
            "TRACE-CMD-V1\n1042\ntracker-alpha-01\ntake_photo\n{\"camera\":\"back\",\"flash\":false,\"quality\":85}\n1776345600\n1776345630\nb3c0e12a-4df1-4a7b-891a-7b2c8e5f1234"
        private const val SPEC_EXPECTED_SHA256 =
            "157b1ac9f9792b7052e5d95c3ec8c8112797e193d43d40db2ffcede6b10599cb"

        private const val RFC8785_CANONICAL_JSON_PREIMAGE =
            "{\"command\":\"START_STREAM\",\"command_id\":1001,\"device_id\":\"pixel-7-001\",\"expires_at\":1776412830,\"issued_at\":1776412800,\"params\":{\"fps\":30,\"quality\":\"1080p\"}}"
        private const val RFC8785_EXPECTED_SHA256 =
            "fd5ae3390946a5c5ef49b5688be16da679b32aa72c60a4eee1ad828b96eedcf8"
    }

    @Test
    fun testCanonicalPreimageDigestMatchesSpecification() {
        val hexString = CommandCryptoUtil.computeSha256Hex(SPEC_TEST_VECTOR_PREIMAGE)

        assertEquals(
            "Preimage SHA-256 digest must byte-for-byte match the Phase 0 Security Gate reference vector",
            SPEC_EXPECTED_SHA256,
            hexString
        )
    }

    @Test
    fun testPureCanonicalJsonPreimageDigest() {
        val hexString = CommandCryptoUtil.computeSha256Hex(RFC8785_CANONICAL_JSON_PREIMAGE)

        assertEquals(
            "RFC 8785 top-level canonical JSON digest must match calculated hash",
            RFC8785_EXPECTED_SHA256,
            hexString
        )
    }

    @Test
    fun testExtendedRfc8785CanonicalJsonAndPreimageDigest() {
        val jsonElement = buildJsonObject {
            put("z", true)
            put("1", "numeric key")
            put("a", buildJsonArray {
                add(buildJsonObject {
                    put("c", buildJsonObject {
                        put("d", "val\"with\"quote\\and\\slash\nand\nnewline\t\b\u000c\u0000\u001f")
                    })
                    put("b", 1)
                })
            })
            put("_", JsonNull)
            put("A", "uppercase")
            put("aa", "double a")
        }

        val canonical = CommandCryptoUtil.canonicalizeJson(jsonElement)
        val expectedCanonical = "{\"1\":\"numeric key\",\"A\":\"uppercase\",\"_\":null,\"a\":[{\"b\":1,\"c\":{\"d\":\"val\\\"with\\\"quote\\\\and\\\\slash\\nand\\nnewline\\t\\b\\f\\u0000\\u001f\"}}],\"aa\":\"double a\",\"z\":true}"
        assertEquals("Canonical JSON must match expected output", expectedCanonical, canonical)

        val hashHex = CommandCryptoUtil.computeSha256Hex(canonical)
        val expectedHash = "9d4dfef2a455ac61377d47f932d71028883be7c581e1510ad4774ddaaeaa470c"
        assertEquals("Extended RFC 8785 SHA-256 digest must match TypeScript output byte-for-byte", expectedHash, hashHex)
    }

    @Test
    fun testFormatCanonicalPreimageMatchesSpecification() {
        val formatted = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 1042L,
            deviceId = "tracker-alpha-01",
            commandName = "take_photo",
            canonicalJsonParams = "{\"camera\":\"back\",\"flash\":false,\"quality\":85}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345630L,
            idempotencyKey = "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234"
        )
        assertEquals(SPEC_TEST_VECTOR_PREIMAGE, formatted)
        assertEquals(SPEC_EXPECTED_SHA256, CommandCryptoUtil.computeSha256Hex(formatted))
    }

    @Test
    fun testEcdsaP256SigningAndVerificationWithAsn1Der() {
        // Generate NIST ECDSA P-256 KeyPair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val data = SPEC_TEST_VECTOR_PREIMAGE.toByteArray(Charsets.UTF_8)

        // Sign using SHA256withECDSA (emits ASN.1/DER)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(data)
        val derSignature = signer.sign()

        // ASN.1 DER signature is typically 70-72 bytes (starts with 0x30 SEQUENCE)
        assertTrue("DER signature must start with ASN.1 SEQUENCE tag 0x30", derSignature[0] == 0x30.toByte())
        assertTrue("DER signature length must be between 68 and 73 bytes", derSignature.size in 68..73)

        // Verify with CommandCryptoUtil
        val isValid = CommandCryptoUtil.verifyEcdsaSignature(
            publicKey = keyPair.public,
            preimageBytes = data,
            derSignature = derSignature
        )

        assertTrue("CommandCryptoUtil verifier must accept valid ASN.1/DER signature", isValid)

        // Tamper test
        val tamperedData = (SPEC_TEST_VECTOR_PREIMAGE + "_tampered").toByteArray(Charsets.UTF_8)
        assertFalse(
            "CommandCryptoUtil verifier must reject tampered preimage data",
            CommandCryptoUtil.verifyEcdsaSignature(
                publicKey = keyPair.public,
                preimageBytes = tamperedData,
                derSignature = derSignature
            )
        )
    }

    @Test
    fun testIeeeP1363ToAsn1DerConversion() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val data = SPEC_TEST_VECTOR_PREIMAGE.toByteArray(Charsets.UTF_8)

        // Generate ASN.1 DER signature
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(data)
        val derSig = signer.sign()

        // Convert DER to IEEE P1363 (64 bytes)
        val p1363Bytes = CommandCryptoUtil.derToP1363(derSig)
        assertEquals("IEEE P1363 raw signature must be exactly 64 bytes", 64, p1363Bytes.size)

        // Convert 64-byte P1363 back to ASN.1 DER via CommandCryptoUtil
        val convertedDer = CommandCryptoUtil.p1363ToDer(p1363Bytes)

        // Verify that CommandCryptoUtil successfully verifies the bridge-converted signature
        assertTrue(
            "Bridge-converted DER signature from IEEE P1363 must successfully verify in CommandCryptoUtil",
            CommandCryptoUtil.verifyEcdsaSignature(
                publicKey = keyPair.public,
                preimageBytes = data,
                derSignature = convertedDer
            )
        )
    }

    @Test
    fun testCommandExpirationAndFreshnessValidation() {
        val issuedAt = 1776412800L
        val ttlSeconds = 300L // 5 minutes
        val expiresAt = issuedAt + ttlSeconds

        // 1. Fresh command in active window
        val nowActive = issuedAt + 120L
        assertTrue(
            "Command within valid TTL must be accepted as fresh",
            CommandCryptoUtil.isCommandFresh(issuedAt, expiresAt, nowActive)
        )

        // 2. Exactly at expiration boundary
        assertTrue(
            "Command exactly at expiresAt must be accepted",
            CommandCryptoUtil.isCommandFresh(issuedAt, expiresAt, expiresAt)
        )

        // 3. 1 second past expiration
        assertFalse(
            "Command 1 second after expiresAt must be rejected as expired",
            CommandCryptoUtil.isCommandFresh(issuedAt, expiresAt, expiresAt + 1L)
        )

        // 4. Clock skew tolerance: 3 seconds before issuedAt should pass with default 5s tolerance
        assertTrue(
            "Command 3s before issuedAt must be accepted under clock skew tolerance",
            CommandCryptoUtil.isCommandFresh(issuedAt, expiresAt, issuedAt - 3L)
        )

        // 5. Premature command beyond tolerance: 10 seconds before issuedAt must fail
        assertFalse(
            "Command 10s before issuedAt must be rejected as premature",
            CommandCryptoUtil.isCommandFresh(issuedAt, expiresAt, issuedAt - 10L)
        )
    }

    @Test
    fun testDecodeEcPublicKeyFromSpkiBytesAndBase64() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val spkiBytes = keyPair.public.encoded
        val base64Spki = java.util.Base64.getEncoder().encodeToString(spkiBytes)

        val decodedFromBytes = CommandCryptoUtil.decodeEcPublicKey(spkiBytes)
        val decodedFromBase64 = CommandCryptoUtil.decodeEcPublicKeyFromBase64(base64Spki)

        assertEquals("Algorithm must be EC", "EC", decodedFromBytes.algorithm)
        assertEquals("Format must be X.509", "X.509", decodedFromBytes.format)
        assertEquals(keyPair.public, decodedFromBytes)
        assertEquals(keyPair.public, decodedFromBase64)

        // Verify signatures verify cleanly with decoded public key
        val sampleData = "test-payload-for-signature".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(sampleData)
        val signatureBytes = sig.sign()

        sig.initVerify(decodedFromBase64)
        sig.update(sampleData)
        assertTrue("Signature must verify with decoded public key", sig.verify(signatureBytes))
    }

    @Test
    fun testDecodeEcPublicKeyRejectsCorruptedData() {
        val corruptBytes = byteArrayOf(0x30, 0x10, 0x01, 0x02)
        try {
            CommandCryptoUtil.decodeEcPublicKey(corruptBytes)
            org.junit.Assert.fail("Expected IllegalArgumentException on corrupt SPKI bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Failed to decode X.509 EC public key") == true)
        }
    }

    @Test
    fun testMapPinnedKeyProviderFromBase64Map() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair1 = kpg.generateKeyPair()
        val keyPair2 = kpg.generateKeyPair()

        val base64Key1 = java.util.Base64.getEncoder().encodeToString(keyPair1.public.encoded)
        val base64Key2 = java.util.Base64.getEncoder().encodeToString(keyPair2.public.encoded)

        val provider = com.livetracker.controller.core.tracker.command.crypto.MapPinnedKeyProvider.fromBase64Map(
            mapOf(
                "key-v1" to base64Key1,
                "key-v2" to base64Key2
            )
        )

        assertEquals(keyPair1.public, provider.getPublicKey("key-v1"))
        assertEquals(keyPair2.public, provider.getPublicKey("key-v2"))
        assertNull(provider.getPublicKey("key-unknown"))
    }

    @Test
    fun testTracePinnedKeyProviderResolvesCanonicalServerKey() {
        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createDefault()
        val pubKey = provider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        assertNotNull("Canonical server key must be resolved by default provider", pubKey)
        assertEquals("EC", pubKey?.algorithm)
        assertEquals("X.509", pubKey?.format)
        assertTrue(provider.hasKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID))
        assertEquals(1, provider.keyCount())
    }

    @Test
    fun testTracePinnedKeyProviderFailsClosedOnUnknownOrMissingKey() {
        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createDefault()
        assertNull("Unknown key ID must return null", provider.getPublicKey("unknown-key-id-999"))
        assertFalse(provider.hasKey("unknown-key-id-999"))
        assertNull("Empty provider must return null for canonical key", com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.EMPTY.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID))
    }

    @Test
    fun testTracePinnedKeyProviderHandlesMalformedKeysWithoutCrashing() {
        val mapWithMalformed = mapOf(
            "corrupt-b64" to "ThisIsNotBase64@@@",
            "corrupt-spki" to java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x30, 0x05, 0x01, 0x02, 0x03)),
            com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID to com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.DEV_SERVER_PUBLIC_KEY_SPKI_BASE64
        )
        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.fromBase64Map(mapWithMalformed)
        assertNull("Malformed b64 key must fail closed", provider.getPublicKey("corrupt-b64"))
        assertNull("Malformed spki key must fail closed", provider.getPublicKey("corrupt-spki"))
        assertNotNull("Valid key must still be resolved", provider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID))
        assertEquals(1, provider.keyCount())
    }

    @Test
    fun testTracePinnedKeyProviderValidSignatureVerification() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.fromSingleKey(
            com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID,
            keyPair.public
        )

        val sampleData = "canonical-preimage-data".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(sampleData)
        val signatureBytes = sig.sign()

        val trustedKey = provider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        assertNotNull(trustedKey)
        val verified = CommandCryptoUtil.verifyEcdsaSignature(trustedKey!!, sampleData, signatureBytes)
        assertTrue("Valid signature must verify against trusted key", verified)

        // Corrupted signature must fail verification
        val corruptedSig = signatureBytes.clone()
        corruptedSig[corruptedSig.size - 2] = (corruptedSig[corruptedSig.size - 2].toInt() xor 0xFF).toByte()
        val verifiedCorrupted = CommandCryptoUtil.verifyEcdsaSignature(trustedKey, sampleData, corruptedSig)
        assertFalse("Corrupted signature must fail verification", verifiedCorrupted)
    }

    @Test
    fun testTracePinnedKeyProviderRejectsMismatchedKeyPair() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val legitimateKeyPair = kpg.generateKeyPair()
        val attackerKeyPair = kpg.generateKeyPair()

        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.fromSingleKey(
            com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID,
            legitimateKeyPair.public
        )

        val sampleData = "canonical-wire-preimage-data".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(attackerKeyPair.private) // Signed by different keypair!
        sig.update(sampleData)
        val attackerSignatureBytes = sig.sign()

        val trustedKey = provider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        assertNotNull(trustedKey)
        val verified = CommandCryptoUtil.verifyEcdsaSignature(trustedKey!!, sampleData, attackerSignatureBytes)
        assertFalse("Signature signed by mismatched keypair must fail verification", verified)
    }

    @Test
    fun testTracePinnedKeyProviderWirePreimageEndToEndVerification() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val spkiBase64 = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val provider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.fromBase64Map(
            mapOf(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID to spkiBase64)
        )

        val canonicalPreimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = 1042L,
            deviceId = "pixel-7-001",
            commandName = "start_camera",
            canonicalJsonParams = "{\"fps\":30,\"quality\":\"high\"}",
            issuedAtEpochSec = 1776345600L,
            expiresAtEpochSec = 1776345900L,
            idempotencyKey = "b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234"
        )

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(canonicalPreimage.toByteArray(Charsets.UTF_8))
        val derSig = sig.sign()

        val resolvedKey = provider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        assertNotNull(resolvedKey)
        assertTrue(
            "Valid canonical preimage wire signature must verify cleanly",
            CommandCryptoUtil.verifyEcdsaSignature(resolvedKey!!, canonicalPreimage.toByteArray(Charsets.UTF_8), derSig)
        )

        // P1363 conversion round-trip check
        val p1363 = CommandCryptoUtil.derToP1363(derSig)
        val convertedDer = CommandCryptoUtil.p1363ToDer(p1363)
        assertTrue(
            "Re-converted DER signature must also verify cleanly",
            CommandCryptoUtil.verifyEcdsaSignature(resolvedKey, canonicalPreimage.toByteArray(Charsets.UTF_8), convertedDer)
        )
    }

    @Test
    fun testTracePinnedKeyProviderEnvironmentSeparation() {
        // 1. Development provider has dev key configured
        val devProvider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createDevelopmentProvider()
        assertEquals("Dev provider must hold 1 key", 1, devProvider.keyCount())
        assertNotNull(
            "Dev provider must resolve canonical key ID",
            devProvider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )

        // 2. Unprovisioned production provider fails closed (0 keys)
        val prodProviderDefault = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createProductionProvider(null)
        assertEquals("Unprovisioned prod provider must hold 0 keys", 0, prodProviderDefault.keyCount())
        assertNull(
            "Unprovisioned prod provider must return null (fail closed)",
            prodProviderDefault.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )

        // 2b. createDefault(isDevelopment = false) strictly fails closed (0 keys)
        val defaultFailClosedProvider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createDefault(isDevelopment = false)
        assertEquals("createDefault(isDevelopment = false) must hold 0 keys (fail closed)", 0, defaultFailClosedProvider.keyCount())
        assertNull(
            "createDefault(isDevelopment = false) must return null for key lookup",
            defaultFailClosedProvider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )

        // 2c. createDefault(isDevelopment = true) provides development key
        val defaultDevProvider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createDefault(isDevelopment = true)
        assertEquals("createDefault(isDevelopment = true) must hold 1 key", 1, defaultDevProvider.keyCount())
        assertNotNull(
            "createDefault(isDevelopment = true) must resolve canonical key ID",
            defaultDevProvider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )

        // 3. createForEnvironment with isDevelopment = false and no prod key fails closed
        val envProdProvider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createForEnvironment(isDevelopment = false)
        assertEquals("Environment prod provider without key must hold 0 keys", 0, envProdProvider.keyCount())
        assertNull(
            "Environment prod provider must return null for key lookup",
            envProdProvider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )

        // 4. Provisioned production provider accepts verified key
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val prodSpki = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)

        val provisionedProdProvider = com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.createProductionProvider(prodSpki)
        assertEquals("Provisioned prod provider must hold 1 key", 1, provisionedProdProvider.keyCount())
        assertNotNull(
            "Provisioned prod provider must resolve canonical key ID",
            provisionedProdProvider.getPublicKey(com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
        )
    }
}
