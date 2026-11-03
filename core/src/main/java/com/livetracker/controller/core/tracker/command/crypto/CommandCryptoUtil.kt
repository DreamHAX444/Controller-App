package com.livetracker.controller.core.tracker.command.crypto

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Controller-side cryptographic utilities adhering strictly to Phase 0 Security Gate:
 * - RFC 8785 JSON Canonicalization Scheme (JCS)
 * - 8-line deterministic wire preimage formatting
 * - NIST ECDSA P-256 (`SHA256withECDSA`) signature verification
 * - IEEE P1363 (64-byte WebCrypto) <-> ASN.1/DER (70-72 byte JCA) bridge adapter
 */
object CommandCryptoUtil {

    private const val WIRE_HEADER = "TRACE-CMD-V1"

    /**
     * Formats the exact 8-line canonical wire preimage (LF delimited, UTF-8).
     */
    fun formatCanonicalPreimage(
        commandId: Long,
        deviceId: String,
        commandName: String,
        canonicalJsonParams: String,
        issuedAtEpochSec: Long,
        expiresAtEpochSec: Long,
        idempotencyKey: String
    ): String {
        return buildString {
            append(WIRE_HEADER).append('\n')
            append(commandId).append('\n')
            append(deviceId).append('\n')
            append(commandName).append('\n')
            append(canonicalJsonParams).append('\n')
            append(issuedAtEpochSec).append('\n')
            append(expiresAtEpochSec).append('\n')
            append(idempotencyKey)
        }
    }

    /**
     * Computes the SHA-256 digest of the canonical preimage.
     */
    fun computeSha256(preimage: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(preimage.toByteArray(StandardCharsets.UTF_8))
    }

    fun computeSha256Hex(preimage: String): String {
        return computeSha256(preimage).joinToString("") { "%02x".format(it) }
    }

    /**
     * RFC 8785 Canonical JSON serializer:
     * - Object keys lexicographically sorted
     * - No whitespace around `:` or `,`
     * - Standard UTF-8
     */
    fun canonicalizeJson(element: JsonElement): String {
        return when (element) {
            is JsonObject -> {
                val sortedKeys = element.keys.sorted()
                val entries = sortedKeys.joinToString(",") { key ->
                    "\"${escapeJsonString(key)}\":${canonicalizeJson(element[key] ?: JsonNull)}"
                }
                "{$entries}"
            }
            is JsonArray -> {
                val items = element.joinToString(",") { canonicalizeJson(it) }
                "[$items]"
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    "\"${escapeJsonString(element.content)}\""
                } else {
                    element.content
                }
            }
            is JsonNull -> "null"
        }
    }

    private fun escapeJsonString(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code in 0..0x1f) {
                        append("\\u%04x".format(c.code))
                    } else {
                        append(c)
                    }
                }
            }
        }
    }

    /**
     * Verifies NIST ECDSA P-256 signature (ASN.1 DER format) using standard JCA.
     */
    fun verifyEcdsaSignature(
        publicKey: PublicKey,
        preimageBytes: ByteArray,
        derSignature: ByteArray
    ): Boolean {
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(preimageBytes)
            verifier.verify(derSignature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Converts raw 64-byte IEEE P1363 signature (WebCrypto) to ASN.1 DER sequence (JCA).
     */
    fun p1363ToDer(p1363: ByteArray): ByteArray {
        require(p1363.size == 64) { "P1363 signature must be exactly 64 bytes" }
        val r = BigInteger(1, p1363.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, p1363.copyOfRange(32, 64)).toByteArray()

        val totalLen = 2 + r.size + 2 + s.size
        val der = ByteArray(2 + totalLen)
        der[0] = 0x30.toByte() // SEQUENCE
        der[1] = totalLen.toByte()
        der[2] = 0x02.toByte() // INTEGER
        der[3] = r.size.toByte()
        System.arraycopy(r, 0, der, 4, r.size)
        val sOffset = 4 + r.size
        der[sOffset] = 0x02.toByte()
        der[sOffset + 1] = s.size.toByte()
        System.arraycopy(s, 0, der, sOffset + 2, s.size)
        return der
    }

    /**
     * Converts ASN.1 DER signature to raw 64-byte IEEE P1363.
     */
    fun derToP1363(der: ByteArray): ByteArray {
        var offset = 2
        require(der[0] == 0x30.toByte()) { "Not a DER sequence" }
        require(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for R" }
        val rLen = der[offset + 1].toInt()
        val rBytes = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen

        require(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for S" }
        val sLen = der[offset + 1].toInt()
        val sBytes = der.copyOfRange(offset + 2, offset + 2 + sLen)

        val p1363 = ByteArray(64)
        val rBigInt = BigInteger(1, rBytes).toByteArray()
        val sBigInt = BigInteger(1, sBytes).toByteArray()

        val rTrimmed = if (rBigInt.size > 32) rBigInt.copyOfRange(rBigInt.size - 32, rBigInt.size) else rBigInt
        val sTrimmed = if (sBigInt.size > 32) sBigInt.copyOfRange(sBigInt.size - 32, sBigInt.size) else sBigInt

        System.arraycopy(rTrimmed, 0, p1363, 32 - rTrimmed.size, rTrimmed.size)
        System.arraycopy(sTrimmed, 0, p1363, 64 - sTrimmed.size, sTrimmed.size)
        return p1363
    }

    /**
     * Validates command temporal freshness with a standard 5-second clock skew tolerance.
     */
    fun isCommandFresh(
        issuedAtEpochSec: Long,
        expiresAtEpochSec: Long,
        currentEpochSec: Long,
        clockSkewToleranceSec: Long = 5L
    ): Boolean {
        val notPremature = currentEpochSec >= (issuedAtEpochSec - clockSkewToleranceSec)
        val notExpired = currentEpochSec <= expiresAtEpochSec
        return notPremature && notExpired
    }

    /**
     * Decodes an EC (NIST P-256) public key from standard X.509 SPKI bytes using native JCA.
     */
    fun decodeEcPublicKey(spkiBytes: ByteArray): PublicKey {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val keySpec = X509EncodedKeySpec(spkiBytes)
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to decode X.509 EC public key: ${e.message}", e)
        }
    }

    /**
     * Decodes an EC (NIST P-256) public key from standard Base64-encoded X.509 SPKI.
     * Tolerates PEM headers/trailers and whitespace.
     */
    fun decodeEcPublicKeyFromBase64(base64Spki: String): PublicKey {
        return try {
            val clean = base64Spki.trim()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            val bytes = java.util.Base64.getDecoder().decode(clean)
            decodeEcPublicKey(bytes)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to decode Base64 EC public key: ${e.message}", e)
        }
    }

    /**
     * Decodes an EC (NIST P-256) private key from standard Base64-encoded PKCS#8.
     */
    fun decodeEcPrivateKeyFromPkcs8Base64(base64Pkcs8: String): java.security.PrivateKey {
        return try {
            val clean = base64Pkcs8.trim()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            val bytes = java.util.Base64.getDecoder().decode(clean)
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(bytes)
            KeyFactory.getInstance("EC").generatePrivate(keySpec)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to decode Base64 PKCS#8 EC private key: ${e.message}", e)
        }
    }

    /**
     * Signs preimage bytes using NIST ECDSA P-256 (`SHA256withECDSA`), returning ASN.1 DER bytes.
     */
    fun signEcdsaP256(privateKey: java.security.PrivateKey, preimageBytes: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(preimageBytes)
        return signer.sign()
    }
}
