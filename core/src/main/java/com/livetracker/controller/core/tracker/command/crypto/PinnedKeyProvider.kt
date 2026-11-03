package com.livetracker.controller.core.tracker.command.crypto

import java.security.PublicKey

/**
 * Resolves trusted server public keys by their unique key identifier.
 * Adheres strictly to the Phase 0 Security Gate: unknown key_ids return null
 * to trigger fail-closed signature verification.
 */
fun interface PinnedKeyProvider {
    fun getPublicKey(keyId: String): PublicKey?
}

/**
 * In-memory key provider supporting multi-key rotation and test fixtures.
 */
class MapPinnedKeyProvider(
    private val keys: Map<String, PublicKey> = emptyMap()
) : PinnedKeyProvider {

    constructor(singleKeyId: String, key: PublicKey) : this(mapOf(singleKeyId to key))

    override fun getPublicKey(keyId: String): PublicKey? = keys[keyId]

    fun isEmpty(): Boolean = keys.isEmpty()

    fun containsKey(keyId: String): Boolean = keys.containsKey(keyId)

    companion object {
        val EMPTY = MapPinnedKeyProvider(emptyMap())

        fun fromBase64Map(base64Keys: Map<String, String>): MapPinnedKeyProvider {
            val decodedMap = base64Keys.mapValues { (_, b64) ->
                CommandCryptoUtil.decodeEcPublicKeyFromBase64(b64)
            }
            return MapPinnedKeyProvider(decodedMap)
        }
    }
}
