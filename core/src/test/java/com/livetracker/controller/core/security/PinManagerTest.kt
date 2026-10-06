package com.livetracker.controller.core.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinManagerTest {

    private lateinit var fakeStorage: SecureStorage
    private lateinit var pinManager: PinManager

    @Before
    fun setup() {
        fakeStorage = FakeSecureStorage()
        pinManager = PinManager(fakeStorage)
    }

    @Test
    fun testPinNotSetInitially() {
        assertFalse(pinManager.isPinSet())
    }

    @Test
    fun testSetPinAndVerifySuccess() {
        pinManager.setPin("1234")
        assertTrue(pinManager.isPinSet())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testVerifyIncorrectPin() {
        pinManager.setPin("1234")
        assertFalse(pinManager.verifyPin("4321"))
    }

    @Test
    fun testLockoutAfterMaxAttempts() {
        pinManager.setPin("1234")
        // Max attempts is 5
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }
        
        assertTrue(pinManager.isLockedOut())
        // Even the correct PIN should fail while locked out
        assertFalse(pinManager.verifyPin("1234"))
    }
}

class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, Any>()

    override fun getString(key: String, defaultValue: String?): String? = map[key] as? String ?: defaultValue
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) { map[key] = value }

    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun putLong(key: String, value: Long) { map[key] = value }

    override fun clear() { map.clear() }
}
