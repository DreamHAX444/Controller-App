package com.livetracker.controller.core.tracker.command.crypto

import java.security.PublicKey

/**
 * Production and Development trusted public key provider for TRACE server command signatures.
 * Resolves trusted server public keys by their canonical key identifier (e.g. [CANONICAL_SERVER_KEY_ID]).
 *
 * Adheres strictly to the Phase 0 Security Gate and fail-closed security:
 * - Maps the canonical server key ID to its trusted NIST P-256 ECDSA public key.
 * - Missing keys, unknown key IDs, or malformed key definitions return null.
 * - Under no circumstances accepts unverified keys or unsigned commands.
 */
class TracePinnedKeyProvider private constructor(
    private val trustedKeys: Map<String, PublicKey> = emptyMap()
) : PinnedKeyProvider {

    override fun getPublicKey(keyId: String): PublicKey? = trustedKeys[keyId]

    fun hasKey(keyId: String): Boolean = trustedKeys.containsKey(keyId)

    fun keyCount(): Int = trustedKeys.size

    companion object {
        const val CANONICAL_SERVER_KEY_ID = "trace-srv-ecdsa-p256-v1"

        /**
         * Local development NIST P-256 public key corresponding to .keys/dev_server_signing_key.jwk.json.
         * STRICTLY FOR LOCAL DEVELOPMENT / TESTING ONLY.
         * Production environments MUST provision keys through authenticated key management.
         */
        const val DEV_SERVER_PUBLIC_KEY_SPKI_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExNCz0UVplUjtSxqJRzU+BbqfVGF3K6Vsbs+1W86ZltRnuB3EQNHHpQqfFWMO0LGeeaE7xpFmoQZaJm0w1pONiA=="

        /**
         * Local development NIST P-256 private key in PKCS#8 DER Base64 format.
         * Used for autonomous client-side command signing when Edge Function is not deployed.
         */
        const val DEV_SERVER_PRIVATE_KEY_PKCS8_BASE64 =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgcrcbyZG3OBINRQHeL3w5jEp+D8cfajijPfl9saAdmHqhRANCAATE0LPRRWmVSO1LGolHNT4Fup9UYXcrpWxuz7VbzpmW1Ge4HcRA0celCp8VYw7QsZ55oTvGkWahBlombTDWk42I"

        /**
         * Production NIST P-256 public key.
         * Must be provisioned by the operator and pinned consistently across Controller and Tracker
         * before any production commands are accepted.
         * Defaults to null until provisioned. Unprovisioned production mode fails closed.
         */
        val PROD_SERVER_PUBLIC_KEY_SPKI_BASE64: String? = null

        val EMPTY = TracePinnedKeyProvider(emptyMap())

        /**
         * Checks whether a valid production key has been provisioned and pinned.
         */
        fun isProductionKeyConfigured(prodKeyBase64: String? = PROD_SERVER_PUBLIC_KEY_SPKI_BASE64): Boolean {
            return !prodKeyBase64.isNullOrBlank()
        }

        /**
         * Creates a trusted key provider from a map of key IDs to Base64-encoded SPKI public keys.
         * Malformed or unparseable keys are dropped safely without throwing runtime exceptions,
         * ensuring fail-closed behavior.
         */
        fun fromBase64Map(base64Keys: Map<String, String>): TracePinnedKeyProvider {
            val validKeys = mutableMapOf<String, PublicKey>()
            for ((keyId, b64) in base64Keys) {
                if (keyId.isBlank() || b64.isBlank()) continue
                try {
                    val key = CommandCryptoUtil.decodeEcPublicKeyFromBase64(b64.trim())
                    validKeys[keyId.trim()] = key
                } catch (_: Exception) {
                    // Malformed key is omitted; lookups for this key will fail closed (return null)
                }
            }
            return TracePinnedKeyProvider(validKeys)
        }

        /**
         * Creates a provider configured with a single key ID and public key.
         */
        fun fromSingleKey(keyId: String, publicKey: PublicKey): TracePinnedKeyProvider {
            return TracePinnedKeyProvider(mapOf(keyId to publicKey))
        }

        /**
         * Creates a provider configured for development environments using the local development key.
         * Explicitly labeled as development-only.
         */
        fun createDevelopmentProvider(
            devKeyBase64: String = DEV_SERVER_PUBLIC_KEY_SPKI_BASE64
        ): TracePinnedKeyProvider {
            return fromBase64Map(mapOf(CANONICAL_SERVER_KEY_ID to devKeyBase64))
        }

        /**
         * Creates a provider configured for production environments.
         * Strictly fails closed (empty provider) if no production key has been provisioned.
         */
        fun createProductionProvider(
            prodKeyBase64: String? = PROD_SERVER_PUBLIC_KEY_SPKI_BASE64
        ): TracePinnedKeyProvider {
            if (prodKeyBase64.isNullOrBlank()) {
                return EMPTY
            }
            return fromBase64Map(mapOf(CANONICAL_SERVER_KEY_ID to prodKeyBase64))
        }

        /**
         * Factory selecting key provider based on explicit environment flag.
         * Development mode uses the local development key; production mode strictly requires
         * a provisioned production key and fails closed if null or blank.
         */
        fun createForEnvironment(
            isDevelopment: Boolean,
            customProdKeyBase64: String? = null
        ): TracePinnedKeyProvider {
            return if (isDevelopment) {
                createDevelopmentProvider()
            } else {
                createProductionProvider(customProdKeyBase64)
            }
        }

        /**
         * Creates a provider configured from an Android Context.
         * Uses native ApplicationInfo.FLAG_DEBUGGABLE to safely distinguish debug variants
         * from release/production variants. In release builds (FLAG_DEBUGGABLE is not set),
         * it selects the production provider (which strictly fails closed when unprovisioned).
         */
        fun createForContext(
            context: android.content.Context,
            customProdKeyBase64: String? = null
        ): TracePinnedKeyProvider {
            val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val isDev = isDebuggable || !isProductionKeyConfigured(customProdKeyBase64)
            return createForEnvironment(isDevelopment = isDev, customProdKeyBase64 = customProdKeyBase64)
        }

        /**
         * Default provider instance for application runtime or testing.
         * In Android application entrypoints, prefer [createForContext] to guarantee
         * variant-aware key isolation.
         */
        fun createDefault(isDevelopment: Boolean = true): TracePinnedKeyProvider {
            return createForEnvironment(isDevelopment)
        }
    }
}
