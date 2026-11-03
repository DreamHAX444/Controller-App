package com.livetracker.controller.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager @JvmOverloads constructor(
    private val secureStorage: SecureStorage,
    private val timeProvider: TimeProvider = DefaultTimeProvider
) {

    // Secondary constructor for backward compatibility with tests or callers providing () -> Long
    constructor(
        secureStorage: SecureStorage,
        wallClockProvider: () -> Long
    ) : this(
        secureStorage,
        object : TimeProvider {
            override fun currentTimeMs(): Long = wallClockProvider()
            override fun elapsedRealtimeMs(): Long = wallClockProvider()
            override fun bootCount(): Int = 1
        }
    )

    companion object {
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_LOCKOUT_DEADLINE_MS = "pin_lockout_deadline_ms"
        private const val KEY_LOCKOUT_BOOT_COUNT = "pin_lockout_boot_count"
        private const val KEY_LOCKOUT_TIME = "pin_lockout_time" // Legacy key for cleanup

        private const val ITERATIONS = 100000 // Increased for offline brute-force resistance
        private const val KEY_LENGTH = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30000L // 30 seconds
    }

    fun isPinSet(): Boolean {
        return secureStorage.getString(KEY_HASH) != null && secureStorage.getString(KEY_SALT) != null
    }

    fun isLockedOut(): Boolean {
        val deadline = secureStorage.getLong(KEY_LOCKOUT_DEADLINE_MS, 0L)
        if (deadline <= 0L) {
            // Check legacy key if present
            val legacyDeadline = secureStorage.getLong(KEY_LOCKOUT_TIME, 0L)
            if (legacyDeadline <= 0L) return false
            val nowWall = timeProvider.currentTimeMs()
            if (nowWall < legacyDeadline) return true
            clearLockoutRecord()
            return false
        }

        val storedBootCount = secureStorage.getInt(KEY_LOCKOUT_BOOT_COUNT, -1)
        val currentBootCount = timeProvider.bootCount()
        val nowElapsed = timeProvider.elapsedRealtimeMs()
        val effectiveDuration = getEffectiveLockoutDuration()

        // Detect if device rebooted during active lockout (either boot count changed, or monotonic clock reset backward)
        if (storedBootCount != -1 && (storedBootCount != currentBootCount || nowElapsed < (deadline - effectiveDuration * 2))) {
            // Re-anchor lockout duration to the new boot session to prevent reboot bypass or deadlock
            val newDeadline = nowElapsed + effectiveDuration
            secureStorage.putInt(KEY_LOCKOUT_BOOT_COUNT, currentBootCount)
            secureStorage.putLong(KEY_LOCKOUT_DEADLINE_MS, newDeadline)
            secureStorage.putLong(KEY_LOCKOUT_TIME, timeProvider.currentTimeMs() + effectiveDuration)
            return true
        }

        if (nowElapsed < deadline) {
            return true
        } else {
            // Lockout expired, clear lockout record while preserving attempt count for progressive backoff
            clearLockoutRecord()
            return false
        }
    }

    fun getRemainingLockoutTimeMs(): Long {
        if (!isLockedOut()) return 0L
        val deadline = secureStorage.getLong(KEY_LOCKOUT_DEADLINE_MS, 0L)
        if (deadline > 0L) {
            val nowElapsed = timeProvider.elapsedRealtimeMs()
            val remaining = deadline - nowElapsed
            return if (remaining > 0L) remaining else 0L
        }
        val legacyDeadline = secureStorage.getLong(KEY_LOCKOUT_TIME, 0L)
        val nowWall = timeProvider.currentTimeMs()
        val remaining = legacyDeadline - nowWall
        return if (remaining > 0L) remaining else 0L
    }

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }

        val salt = generateSalt()
        val hash = hashPin(pin, salt)

        secureStorage.putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
        secureStorage.putString(KEY_HASH, Base64.getEncoder().encodeToString(hash))
        resetLockout()
    }

    fun verifyPin(pin: String): Boolean {
        if (isLockedOut()) return false

        val storedSaltBase64 = secureStorage.getString(KEY_SALT) ?: return false
        val storedHashBase64 = secureStorage.getString(KEY_HASH) ?: return false

        val storedSalt = Base64.getDecoder().decode(storedSaltBase64)
        val storedHash = Base64.getDecoder().decode(storedHashBase64)

        val inputHash = hashPin(pin, storedSalt)

        val isMatch = MessageDigest.isEqual(storedHash, inputHash) // Constant time comparison

        if (isMatch) {
            resetLockout()
            return true
        } else {
            val failedAttempts = secureStorage.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            secureStorage.putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                val excess = failedAttempts - MAX_FAILED_ATTEMPTS
                val multiplier = 1L shl minOf(excess, 4) // 1x (30s) at 5 attempts, 2x (60s) at 6, 4x (120s) at 7, up to 16x (~8 mins).
                val effectiveDuration = LOCKOUT_DURATION_MS * multiplier
                val nowElapsed = timeProvider.elapsedRealtimeMs()
                val currentBoot = timeProvider.bootCount()
                secureStorage.putLong(KEY_LOCKOUT_DEADLINE_MS, nowElapsed + effectiveDuration)
                secureStorage.putInt(KEY_LOCKOUT_BOOT_COUNT, currentBoot)
                secureStorage.putLong(KEY_LOCKOUT_TIME, timeProvider.currentTimeMs() + effectiveDuration)
            }
            return false
        }
    }

    private fun getEffectiveLockoutDuration(): Long {
        val failedAttempts = secureStorage.getInt(KEY_FAILED_ATTEMPTS, 0)
        val excess = (failedAttempts - MAX_FAILED_ATTEMPTS).coerceAtLeast(0)
        val multiplier = 1L shl minOf(excess, 4)
        return LOCKOUT_DURATION_MS * multiplier
    }

    private fun clearLockoutRecord() {
        secureStorage.putLong(KEY_LOCKOUT_DEADLINE_MS, 0L)
        secureStorage.putInt(KEY_LOCKOUT_BOOT_COUNT, -1)
        secureStorage.putLong(KEY_LOCKOUT_TIME, 0L)
    }

    private fun resetLockout() {
        clearLockoutRecord()
        secureStorage.putInt(KEY_FAILED_ATTEMPTS, 0)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
