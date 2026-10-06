package com.livetracker.controller.core.security

import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(private val secureStorage: SecureStorage) {

    companion object {
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_LOCKOUT_TIME = "pin_lockout_time"

        private const val ITERATIONS = 100000 // Increased for offline brute-force resistance
        private const val KEY_LENGTH = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30000L // 30 seconds
    }

    fun isPinSet(): Boolean {
        return secureStorage.getString(KEY_HASH) != null && secureStorage.getString(KEY_SALT) != null
    }

    fun isLockedOut(): Boolean {
        val lockoutTime = secureStorage.getLong(KEY_LOCKOUT_TIME, 0L)
        if (lockoutTime > 0 && System.currentTimeMillis() < lockoutTime) {
            return true
        } else if (System.currentTimeMillis() >= lockoutTime) {
            // Lockout expired, reset attempts
            if (lockoutTime > 0) {
                secureStorage.putLong(KEY_LOCKOUT_TIME, 0L)
                secureStorage.putInt(KEY_FAILED_ATTEMPTS, 0)
            }
            return false
        }
        return false
    }

    fun getRemainingLockoutTimeMs(): Long {
        val lockoutTime = secureStorage.getLong(KEY_LOCKOUT_TIME, 0L)
        val remaining = lockoutTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        
        secureStorage.putString(KEY_SALT, Base64.getEncoder().encodeToString(salt))
        secureStorage.putString(KEY_HASH, Base64.getEncoder().encodeToString(hash))
        secureStorage.putInt(KEY_FAILED_ATTEMPTS, 0)
        secureStorage.putLong(KEY_LOCKOUT_TIME, 0L)
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
            secureStorage.putInt(KEY_FAILED_ATTEMPTS, 0)
            secureStorage.putLong(KEY_LOCKOUT_TIME, 0L)
            return true
        } else {
            val failedAttempts = secureStorage.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            secureStorage.putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                secureStorage.putLong(KEY_LOCKOUT_TIME, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            }
            return false
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
