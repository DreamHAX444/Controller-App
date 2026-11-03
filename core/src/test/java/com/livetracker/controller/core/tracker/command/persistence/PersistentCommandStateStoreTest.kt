package com.livetracker.controller.core.tracker.command.persistence

import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersistentCommandStateStoreTest {

    private lateinit var fakePrefs: TestSharedPreferences
    private val testDispatcher = UnconfinedTestDispatcher()
    private var testTime = 100_000L

    private fun createStore(): PersistentCommandStateStore {
        return PersistentCommandStateStore(
            prefs = fakePrefs,
            ioDispatcher = testDispatcher,
            timeProvider = { testTime }
        )
    }

    @Before
    fun setUp() {
        fakePrefs = TestSharedPreferences()
        testTime = 100_000L
    }

    @Test
    fun `commands persist to storage and survive instance reconstruction`() = runTest(testDispatcher) {
        val store1 = createStore()
        val command = TrackedCommand(
            idempotencyKey = "uuid-persist-1",
            commandId = 501L,
            deviceId = "pixel-7-001",
            commandName = "start_camera",
            status = CommandStatus.SUBMITTED,
            createdAtEpochMs = testTime
        )

        store1.recordCommand(command)

        // Verify stored in SharedPreferences JSON
        assertTrue(fakePrefs.contains(PersistentCommandStateStore.KEY_COMMANDS))

        // Create completely new store instance from same prefs
        val store2 = createStore()
        val retrieved = store2.getCommand("uuid-persist-1")

        assertNotNull(retrieved)
        assertEquals(501L, retrieved?.commandId)
        assertEquals("pixel-7-001", retrieved?.deviceId)
        assertEquals(CommandStatus.SUBMITTED, retrieved?.status)
    }

    @Test
    fun `terminal status EXECUTED is strictly immutable across re-registration`() = runTest(testDispatcher) {
        val store = createStore()
        val command = TrackedCommand(
            idempotencyKey = "uuid-term-exec",
            commandId = 502L,
            deviceId = "tracker-1",
            commandName = "take_photo",
            status = CommandStatus.EXECUTED,
            resultMessage = "Photo captured"
        )
        store.recordCommand(command)

        // Attempt duplicate registration with PENDING status (e.g., re-submission race)
        val duplicateAttempt = command.copy(status = CommandStatus.PENDING, resultMessage = null)
        store.recordCommand(duplicateAttempt)

        val retrieved = store.getCommand("uuid-term-exec")
        assertEquals(CommandStatus.EXECUTED, retrieved?.status)
        assertEquals("Photo captured", retrieved?.resultMessage)

        // Attempt status update to FAILED
        store.updateStatus("uuid-term-exec", CommandStatus.FAILED, "Late failure")
        val afterUpdate = store.getCommand("uuid-term-exec")
        assertEquals(CommandStatus.EXECUTED, afterUpdate?.status)
    }

    @Test
    fun `monotonic guard prevents SUBMITTED from overwriting ACKNOWLEDGED`() = runTest(testDispatcher) {
        val store = createStore()
        val command = TrackedCommand(
            idempotencyKey = "uuid-mono-1",
            commandId = 503L,
            deviceId = "tracker-1",
            commandName = "start_audio",
            status = CommandStatus.ACKNOWLEDGED
        )
        store.recordCommand(command)

        // Attempt status update back to SUBMITTED
        store.updateStatus("uuid-mono-1", CommandStatus.SUBMITTED)

        val retrieved = store.getCommand("uuid-mono-1")
        assertEquals(CommandStatus.ACKNOWLEDGED, retrieved?.status)
    }

    @Test
    fun `re-recording pending command increments retry count and preserves sequence id`() = runTest(testDispatcher) {
        val store = createStore()
        val initial = TrackedCommand(
            idempotencyKey = "uuid-retry-1",
            commandId = 504L,
            deviceId = "tracker-1",
            commandName = "request_location",
            status = CommandStatus.PENDING,
            retryCount = 0
        )
        store.recordCommand(initial)

        val retryAttempt = TrackedCommand(
            idempotencyKey = "uuid-retry-1",
            commandId = null, // Sequence id might not be known yet on retry
            deviceId = "tracker-1",
            commandName = "request_location",
            status = CommandStatus.PENDING,
            retryCount = 0
        )
        store.recordCommand(retryAttempt)

        val retrieved = store.getCommand("uuid-retry-1")
        assertEquals(504L, retrieved?.commandId) // Preserved
        assertEquals(1, retrieved?.retryCount)   // Incremented
    }

    @Test
    fun `observePendingCommands emits only active non-terminal commands`() = runTest(testDispatcher) {
        val store = createStore()
        val pendingCmd = TrackedCommand(
            idempotencyKey = "uuid-active",
            deviceId = "tracker-1",
            commandName = "start_screen",
            status = CommandStatus.SUBMITTED
        )
        val executedCmd = TrackedCommand(
            idempotencyKey = "uuid-done",
            deviceId = "tracker-1",
            commandName = "stop_screen",
            status = CommandStatus.EXECUTED
        )

        store.recordCommand(pendingCmd)
        store.recordCommand(executedCmd)

        val pendingList = store.observePendingCommands().first()
        assertEquals(1, pendingList.size)
        assertEquals("uuid-active", pendingList[0].idempotencyKey)
        assertFalse(pendingList[0].status.isTerminal())
    }

    @Test
    fun `corrupted JSON in storage fails safe and creates backup`() = runTest(testDispatcher) {
        // Inject invalid/corrupted JSON string directly into prefs
        fakePrefs.edit().putString(
            PersistentCommandStateStore.KEY_COMMANDS,
            "{\"invalid_json\": true, corrupted...}"
        ).apply()

        val store = createStore()

        // Should not throw, fails safe with empty map
        assertNull(store.getCommand("any-key"))

        // Corrupted payload must be safely backed up
        val backup = fakePrefs.getString(PersistentCommandStateStore.KEY_COMMANDS_BACKUP, null)
        assertNotNull(backup)
        assertTrue(backup!!.contains("corrupted"))
    }

    @Test
    fun `clearOldCommands removes only expired terminal commands`() = runTest(testDispatcher) {
        val store = createStore()
        testTime = 200_000L

        val oldExecuted = TrackedCommand(
            idempotencyKey = "cmd-old-exec",
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.EXECUTED,
            createdAtEpochMs = 100_000L // 100s old
        )
        val freshExecuted = TrackedCommand(
            idempotencyKey = "cmd-fresh-exec",
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.EXECUTED,
            createdAtEpochMs = 195_000L // 5s old
        )
        val oldPending = TrackedCommand(
            idempotencyKey = "cmd-old-pending",
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.PENDING,
            createdAtEpochMs = 100_000L // Non-terminal
        )

        store.recordCommand(oldExecuted)
        store.recordCommand(freshExecuted)
        store.recordCommand(oldPending)

        // Clear older than 10 seconds (10_000ms)
        val cleared = store.clearOldCommands(olderThanMs = 10_000L)
        assertEquals(1, cleared)

        assertNull(store.getCommand("cmd-old-exec"))
        assertNotNull(store.getCommand("cmd-fresh-exec"))
        assertNotNull(store.getCommand("cmd-old-pending"))
    }

    @Test
    fun `monotonic guard prevents EXECUTED from regressing to SUBMITTED or PENDING`() = runTest(testDispatcher) {
        val store = createStore()
        val command = TrackedCommand(
            idempotencyKey = "cmd-exec-guard",
            commandId = 601L,
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.EXECUTED
        )
        store.recordCommand(command)

        // Attempt regression via updateStatus to SUBMITTED
        store.updateStatus("cmd-exec-guard", CommandStatus.SUBMITTED)
        assertEquals(CommandStatus.EXECUTED, store.getCommand("cmd-exec-guard")?.status)

        // Attempt regression via recordCommand to PENDING
        store.recordCommand(command.copy(status = CommandStatus.PENDING))
        assertEquals(CommandStatus.EXECUTED, store.getCommand("cmd-exec-guard")?.status)
    }

    @Test
    fun `TIMEOUT and FAILED statuses are strictly immutable across re-registration`() = runTest(testDispatcher) {
        val store = createStore()
        val timeoutCmd = TrackedCommand(
            idempotencyKey = "cmd-timeout",
            commandId = 602L,
            deviceId = "tracker-1",
            commandName = "stop_camera",
            status = CommandStatus.TIMEOUT,
            resultMessage = "Network timeout"
        )
        store.recordCommand(timeoutCmd)

        // Re-registration cannot alter terminal TIMEOUT
        store.recordCommand(timeoutCmd.copy(status = CommandStatus.SUBMITTED, resultMessage = null))
        val afterTimeout = store.getCommand("cmd-timeout")
        assertEquals(CommandStatus.TIMEOUT, afterTimeout?.status)
        assertEquals("Network timeout", afterTimeout?.resultMessage)

        val failedCmd = TrackedCommand(
            idempotencyKey = "cmd-failed",
            commandId = 603L,
            deviceId = "tracker-1",
            commandName = "start_audio",
            status = CommandStatus.FAILED,
            resultMessage = "Permission denied"
        )
        store.recordCommand(failedCmd)

        store.updateStatus("cmd-failed", CommandStatus.EXECUTED, "Late executed")
        val afterFailed = store.getCommand("cmd-failed")
        assertEquals(CommandStatus.FAILED, afterFailed?.status)
        assertEquals("Permission denied", afterFailed?.resultMessage)
    }

    @Test
    fun `re-recording pending command preserves original createdAtEpochMs across retries`() = runTest(testDispatcher) {
        val store = createStore()
        val originalTime = 50_000L
        val retryTime = 90_000L

        val initial = TrackedCommand(
            idempotencyKey = "cmd-retry-time",
            commandId = 604L,
            deviceId = "tracker-1",
            commandName = "request_location",
            status = CommandStatus.PENDING,
            createdAtEpochMs = originalTime
        )
        store.recordCommand(initial)

        val retry = TrackedCommand(
            idempotencyKey = "cmd-retry-time",
            commandId = 604L,
            deviceId = "tracker-1",
            commandName = "request_location",
            status = CommandStatus.PENDING,
            createdAtEpochMs = retryTime
        )
        store.recordCommand(retry)

        val retrieved = store.getCommand("cmd-retry-time")
        assertEquals(originalTime, retrieved?.createdAtEpochMs)
        assertEquals(1, retrieved?.retryCount)
    }

    @Test
    fun `observePendingCommands on fresh store instance loads from disk without prior getCommand call`() = runTest(testDispatcher) {
        val store1 = createStore()
        store1.recordCommand(
            TrackedCommand(
                idempotencyKey = "eager-flow-test",
                deviceId = "tracker-1",
                commandName = "start_camera",
                status = CommandStatus.PENDING
            )
        )

        // New instance from same prefs
        val store2 = createStore()
        val pending = store2.observePendingCommands().first()
        assertEquals(1, pending.size)
        assertEquals("eager-flow-test", pending[0].idempotencyKey)
    }
}

/**
 * Thread-safe in-memory SharedPreferences for JVM tests.
 */
private class TestSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? MutableSet<String>) ?: defValues
    }
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = TestEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class TestEditor : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) staging[key] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) toRemove.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearFlag) data.clear()
            toRemove.forEach { data.remove(it) }
            data.putAll(staging)
        }
    }
}
