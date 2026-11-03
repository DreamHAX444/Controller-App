package com.livetracker.controller.core.tracker.command.persistence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommandStateStoreTest {

    private lateinit var store: CommandStateStore

    @Before
    fun setUp() {
        store = InMemoryCommandStateStore()
    }

    @Test
    fun `record command and retrieve by idempotency key`() = runTest {
        val command = TrackedCommand(
            idempotencyKey = "uuid-1234",
            commandId = 101L,
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.SUBMITTED
        )

        val result = store.recordCommand(command)
        assertTrue(result.isSuccess)

        val retrieved = store.getCommand("uuid-1234")
        assertNotNull(retrieved)
        assertEquals(101L, retrieved?.commandId)
        assertEquals(CommandStatus.SUBMITTED, retrieved?.status)
    }

    @Test
    fun `retrieve command by monotonic sequence id`() = runTest {
        val command = TrackedCommand(
            idempotencyKey = "uuid-5678",
            commandId = 202L,
            deviceId = "tracker-2",
            commandName = "request_location",
            status = CommandStatus.SUBMITTED
        )

        store.recordCommand(command)

        val retrieved = store.getCommandBySequenceId(202L)
        assertNotNull(retrieved)
        assertEquals("uuid-5678", retrieved?.idempotencyKey)
    }

    @Test
    fun `terminal status EXECUTED is strictly immutable and ignores further updates`() = runTest {
        val command = TrackedCommand(
            idempotencyKey = "uuid-term-1",
            commandId = 303L,
            deviceId = "tracker-1",
            commandName = "start_audio",
            status = CommandStatus.EXECUTED,
            resultMessage = "Audio started successfully"
        )

        store.recordCommand(command)

        // Attempting to overwrite terminal status with FAILED or SUBMITTED must be ignored
        store.updateStatus("uuid-term-1", CommandStatus.FAILED, "Late error")

        val retrieved = store.getCommand("uuid-term-1")
        assertEquals(CommandStatus.EXECUTED, retrieved?.status)
        assertEquals("Audio started successfully", retrieved?.resultMessage)
    }

    @Test
    fun `terminal status FAILED is strictly immutable and ignores further updates`() = runTest {
        val command = TrackedCommand(
            idempotencyKey = "uuid-term-2",
            commandId = 304L,
            deviceId = "tracker-1",
            commandName = "stop_camera",
            status = CommandStatus.FAILED,
            resultMessage = "Hardware error"
        )

        store.recordCommand(command)

        // Attempting to overwrite terminal status with EXECUTED must be ignored
        store.updateStatus("uuid-term-2", CommandStatus.EXECUTED, "Late executed")

        val retrieved = store.getCommand("uuid-term-2")
        assertEquals(CommandStatus.FAILED, retrieved?.status)
        assertEquals("Hardware error", retrieved?.resultMessage)
    }

    @Test
    fun `clearOldCommands only purges terminal commands older than threshold`() = runTest {
        val oldExecuted = TrackedCommand(
            idempotencyKey = "old-executed",
            commandId = 401L,
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.EXECUTED,
            createdAtEpochMs = 100_000L
        )

        val recentExecuted = TrackedCommand(
            idempotencyKey = "recent-executed",
            commandId = 402L,
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.EXECUTED,
            createdAtEpochMs = System.currentTimeMillis() - 1000L
        )

        val oldPending = TrackedCommand(
            idempotencyKey = "old-pending",
            commandId = 403L,
            deviceId = "tracker-1",
            commandName = "start_camera",
            status = CommandStatus.PENDING,
            createdAtEpochMs = 100_000L
        )

        store.recordCommand(oldExecuted)
        store.recordCommand(recentExecuted)
        store.recordCommand(oldPending)

        val purged = store.clearOldCommands(olderThanMs = 10_000L)
        assertEquals(1, purged) // Only old-executed is purged

        assertNull(store.getCommand("old-executed"))
        assertNotNull(store.getCommand("recent-executed"))
        assertNotNull(store.getCommand("old-pending")) // Non-terminal commands are NEVER purged
    }

    @Test
    fun `monotonic guard prevents SUBMITTED from overwriting ACKNOWLEDGED`() = runTest {
        val command = TrackedCommand(
            idempotencyKey = "uuid-inmem-ack",
            deviceId = "tracker-1",
            commandName = "request_location",
            status = CommandStatus.ACKNOWLEDGED
        )
        store.recordCommand(command)

        // Attempt to regress status back to SUBMITTED
        store.updateStatus("uuid-inmem-ack", CommandStatus.SUBMITTED)

        val retrieved = store.getCommand("uuid-inmem-ack")
        assertEquals(CommandStatus.ACKNOWLEDGED, retrieved?.status)
    }

    @Test
    fun `observePendingCommands filters out terminal commands`() = runTest {
        val pending = TrackedCommand(
            idempotencyKey = "uuid-inmem-pending",
            deviceId = "tracker-1",
            commandName = "start_audio",
            status = CommandStatus.SUBMITTED
        )
        val executed = TrackedCommand(
            idempotencyKey = "uuid-inmem-exec",
            deviceId = "tracker-1",
            commandName = "stop_audio",
            status = CommandStatus.EXECUTED
        )
        store.recordCommand(pending)
        store.recordCommand(executed)

        val pendingList = store.observePendingCommands().first()
        assertEquals(1, pendingList.size)
        assertEquals("uuid-inmem-pending", pendingList[0].idempotencyKey)
    }
}
