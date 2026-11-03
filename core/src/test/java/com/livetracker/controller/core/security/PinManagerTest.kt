package com.livetracker.controller.core.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinManagerTest {

    private lateinit var fakeStorage: SecureStorage
    private lateinit var testTimeProvider: TestTimeProvider
    private lateinit var pinManager: PinManager

    @Before
    fun setup() {
        fakeStorage = FakeSecureStorage()
        testTimeProvider = TestTimeProvider(
            currentTime = 1_000_000L,
            elapsedRealtime = 50_000L,
            bootCount = 1
        )
        pinManager = PinManager(fakeStorage, testTimeProvider)
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
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }

        assertTrue(pinManager.isLockedOut())
        // Even the correct PIN should fail while locked out
        assertFalse(pinManager.verifyPin("1234"))
    }

    @Test
    fun testLockoutExpirationWithMonotonicTime() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }

        assertTrue(pinManager.isLockedOut())
        assertEquals(30000L, pinManager.getRemainingLockoutTimeMs())

        // Advance monotonic time halfway (15 seconds)
        testTimeProvider.advanceTime(15_000L)
        assertTrue(pinManager.isLockedOut())
        assertEquals(15000L, pinManager.getRemainingLockoutTimeMs())

        // Advance past 30 seconds
        testTimeProvider.advanceTime(15_001L)
        assertFalse(pinManager.isLockedOut())
        assertEquals(0L, pinManager.getRemainingLockoutTimeMs())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testClockRollForwardDoesNotBypassLockout() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }

        assertTrue(pinManager.isLockedOut())

        // Attacker advances wall clock forward by 1 hour (simulating Settings date change)
        testTimeProvider.setWallClockTime(testTimeProvider.currentTimeMs() + 3_600_000L)
        // Monotonic time has NOT advanced
        assertTrue("Rolling system clock forward must not bypass active lockout", pinManager.isLockedOut())
        assertEquals(30000L, pinManager.getRemainingLockoutTimeMs())
        assertFalse("Correct PIN must still be rejected while monotonic lockout is active", pinManager.verifyPin("1234"))

        // Only advancing monotonic time expires the lockout
        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testClockRollBackwardDoesNotBypassLockout() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }

        assertTrue(pinManager.isLockedOut())

        // Attacker rolls back clock to the past
        testTimeProvider.setWallClockTime(100_000L)
        assertTrue("Clock rollback must not bypass active lockout", pinManager.isLockedOut())
        assertEquals(30000L, pinManager.getRemainingLockoutTimeMs())
        assertFalse(pinManager.verifyPin("1234"))

        // Monotonic time advancement expires lockout normally
        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testAppRestartDuringLockoutPreservesLockout() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }
        assertTrue(pinManager.isLockedOut())

        // Monotonic time advances by 10 seconds
        testTimeProvider.advanceTime(10_000L)

        // Simulate app process kill & restart with a new PinManager instance (same boot count)
        val restartedPinManager = PinManager(fakeStorage, testTimeProvider)
        assertTrue("Lockout must persist across app process restart", restartedPinManager.isLockedOut())
        assertEquals(20000L, restartedPinManager.getRemainingLockoutTimeMs())
        assertFalse(restartedPinManager.verifyPin("1234"))

        // Remaining 20 seconds elapse
        testTimeProvider.advanceTime(20_001L)
        assertFalse(restartedPinManager.isLockedOut())
        assertEquals(0L, restartedPinManager.getRemainingLockoutTimeMs())
        assertTrue(restartedPinManager.verifyPin("1234"))
    }

    @Test
    fun testDeviceRebootDuringLockoutReanchorsLockout() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }
        assertTrue(pinManager.isLockedOut())

        // Attacker immediately reboots phone (elapsedRealtime resets to 3000ms, bootCount increments)
        testTimeProvider.reboot(newElapsedRealtimeMs = 3_000L)

        // New PinManager instance in the new boot cycle
        val postRebootPinManager = PinManager(fakeStorage, testTimeProvider)

        // Reboot must NOT clear the lockout; it re-anchors for 30 seconds
        assertTrue("Device reboot must not bypass active lockout", postRebootPinManager.isLockedOut())
        assertEquals(30000L, postRebootPinManager.getRemainingLockoutTimeMs())
        assertFalse("PIN verification must fail after reboot while locked out", postRebootPinManager.verifyPin("1234"))

        // Advance 15s in the new boot session
        testTimeProvider.advanceTime(15_000L)
        assertTrue(postRebootPinManager.isLockedOut())
        assertEquals(15000L, postRebootPinManager.getRemainingLockoutTimeMs())

        // Complete the 30s lockout in the new boot session
        testTimeProvider.advanceTime(15_001L)
        assertFalse(postRebootPinManager.isLockedOut())
        assertEquals(0L, postRebootPinManager.getRemainingLockoutTimeMs())
        assertTrue(postRebootPinManager.verifyPin("1234"))
    }

    @Test
    fun testMultipleConsecutiveRebootsKeepLockoutEnforced() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }
        assertTrue(pinManager.isLockedOut())

        // Multiple rapid reboots
        repeat(3) {
            testTimeProvider.reboot(newElapsedRealtimeMs = 1_000L)
            val currentPinManager = PinManager(fakeStorage, testTimeProvider)
            assertTrue("Repeated reboot must not bypass lockout", currentPinManager.isLockedOut())
            assertFalse(currentPinManager.verifyPin("1234"))
        }

        // Wait out the full 30s in the final boot cycle
        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testDeviceRebootWithoutActiveLockoutDoesNotLockout() {
        pinManager.setPin("1234")
        // Only 2 failed attempts (not locked out)
        assertFalse(pinManager.verifyPin("0000"))
        assertFalse(pinManager.verifyPin("0000"))
        assertFalse(pinManager.isLockedOut())

        // Device reboots under normal conditions
        testTimeProvider.reboot(newElapsedRealtimeMs = 5_000L)
        val postRebootPinManager = PinManager(fakeStorage, testTimeProvider)

        assertFalse("Reboot without active lockout must not trigger lockout", postRebootPinManager.isLockedOut())
        assertTrue("Legitimate PIN verification must succeed after reboot", postRebootPinManager.verifyPin("1234"))
    }

    @Test
    fun testMonotonicClockResetWithoutBootCountIncrementReanchorsLockout() {
        pinManager.setPin("1234")
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }
        assertTrue(pinManager.isLockedOut())

        // Monotonic clock resets to 1000ms, but bootCount stays 1 (e.g. boot_count unreadable or emulator reset)
        testTimeProvider.advanceTime(-49_000L) // elapsedRealtime resets from 50_000ms to 1_000ms

        assertTrue("Monotonic clock reset without boot count change must still enforce lockout", pinManager.isLockedOut())
        assertEquals(30000L, pinManager.getRemainingLockoutTimeMs())
        assertFalse(pinManager.verifyPin("1234"))

        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())
        assertTrue(pinManager.verifyPin("1234"))
    }

    @Test
    fun testProgressiveExponentialBackoffLockoutBeyondMaxAttempts() {
        pinManager.setPin("1234")

        // Attempts 1 to 5
        repeat(5) {
            assertFalse(pinManager.verifyPin("0000"))
        }

        // Attempt 5 -> Base lockout is 30,000ms (1x)
        assertTrue(pinManager.isLockedOut())
        assertEquals(30000L, pinManager.getRemainingLockoutTimeMs())

        // Elapse the 30s lockout
        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())

        // Attempt 6 -> 2x multiplier = 60,000ms
        assertFalse(pinManager.verifyPin("0000"))
        assertTrue(pinManager.isLockedOut())
        assertEquals(60000L, pinManager.getRemainingLockoutTimeMs())

        // Elapse the 60s lockout
        testTimeProvider.advanceTime(60_001L)
        assertFalse(pinManager.isLockedOut())

        // Attempt 7 -> 4x multiplier = 120,000ms
        assertFalse(pinManager.verifyPin("0000"))
        assertTrue(pinManager.isLockedOut())
        assertEquals(120000L, pinManager.getRemainingLockoutTimeMs())

        // Elapse the 120s lockout
        testTimeProvider.advanceTime(120_001L)
        assertFalse(pinManager.isLockedOut())

        // Attempt 8 -> 8x multiplier = 240,000ms
        assertFalse(pinManager.verifyPin("0000"))
        assertTrue(pinManager.isLockedOut())
        assertEquals(240000L, pinManager.getRemainingLockoutTimeMs())

        // Elapse the 240s lockout
        testTimeProvider.advanceTime(240_001L)
        assertFalse(pinManager.isLockedOut())

        // Correct PIN resets failed attempts
        assertTrue(pinManager.verifyPin("1234"))
        assertFalse(pinManager.isLockedOut())

        // Next failure starts fresh at 1 attempt (no lockout until 5)
        assertFalse(pinManager.verifyPin("0000"))
        assertFalse(pinManager.isLockedOut())
    }

    @Test
    fun testDeviceRebootDuringExtendedLockoutRespectsEffectiveDuration() {
        pinManager.setPin("1234")

        // 5 initial attempts -> 30s lockout
        repeat(5) { assertFalse(pinManager.verifyPin("0000")) }
        testTimeProvider.advanceTime(30_001L)
        assertFalse(pinManager.isLockedOut())

        // 6th attempt -> 60s lockout
        assertFalse(pinManager.verifyPin("0000"))
        assertTrue(pinManager.isLockedOut())
        assertEquals(60000L, pinManager.getRemainingLockoutTimeMs())

        // Attacker reboots phone during 60s lockout
        testTimeProvider.reboot(newElapsedRealtimeMs = 2_000L)
        val postRebootManager = PinManager(fakeStorage, testTimeProvider)

        // Lockout must re-anchor for 60 seconds (respecting effectiveDuration)
        assertTrue(postRebootManager.isLockedOut())
        assertEquals(60000L, postRebootManager.getRemainingLockoutTimeMs())
    }
}

class TestTimeProvider(
    private var currentTime: Long = 1_000_000L,
    private var elapsedRealtime: Long = 50_000L,
    private var bootCount: Int = 1
) : TimeProvider {

    override fun currentTimeMs(): Long = currentTime
    override fun elapsedRealtimeMs(): Long = elapsedRealtime
    override fun bootCount(): Int = bootCount

    fun advanceTime(elapsedDeltaMs: Long, wallDeltaMs: Long = elapsedDeltaMs) {
        elapsedRealtime += elapsedDeltaMs
        currentTime += wallDeltaMs
    }

    fun setWallClockTime(newTimeMs: Long) {
        currentTime = newTimeMs
    }

    fun reboot(newElapsedRealtimeMs: Long = 5_000L) {
        bootCount += 1
        elapsedRealtime = newElapsedRealtimeMs
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
