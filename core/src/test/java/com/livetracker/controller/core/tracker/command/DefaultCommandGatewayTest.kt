package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCommandGatewayTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createValidSession(): Session {
        val user = User(
            id = "test-user-id",
            displayName = "Admin",
            role = Role.ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = true,
            createdAt = 1000L,
            expiresAt = 100_000L
        )
    }

    @Test
    fun `submitCommand completes instantly via command_ack broadcast without select polling`() = testScope.runTest {
        val idKey = "idemp-ack-success"
        val cmd = TrackerCommand.RequestLocation(deviceId = TrackerId("dev-1"))
        val session = createValidSession()

        // Mock delegate returning Accepted
        val delegate = object : CommandGateway {
            override suspend fun submitCommand(
                command: TrackerCommand,
                session: Session,
                idempotencyKey: String
            ): CommandResult {
                return CommandResult.Accepted(commandId = "seq-101", deviceId = command.deviceId)
            }
        }

        val gateway = DefaultCommandGateway(
            delegate = delegate,
            scope = testScope,
            defaultTimeoutMs = 5000L
        )

        var finalResult: CommandResult? = null
        val job = launch {
            finalResult = gateway.submitCommand(cmd, session, idKey)
        }
        advanceTimeBy(10)

        // Verify pending deferred is registered
        assertTrue("pendingAcks must contain idempotency key", gateway.pendingAcks.containsKey(idKey))

        // Incoming Realtime command_ack envelope arrives
        val ack = CommandAckEnvelope(
            idempotencyKey = idKey,
            commandId = "seq-101",
            status = "COMPLETED",
            resultPayload = "{\"lat\":37.7749,\"lng\":-122.4194}"
        )
        gateway.handleIncomingAck(ack)
        advanceUntilIdle()

        job.join()
        assertTrue("Result must be Completed", finalResult is CommandResult.Completed)
        val completed = finalResult as CommandResult.Completed
        assertEquals("{\"lat\":37.7749,\"lng\":-122.4194}", completed.payload)
        assertFalse("pendingAcks must be cleared after completion", gateway.pendingAcks.containsKey(idKey))
    }

    @Test
    fun `submitCommand returns Failed when command_ack status is FAILED`() = testScope.runTest {
        val idKey = "idemp-ack-failure"
        val cmd = TrackerCommand.RequestLocation(deviceId = TrackerId("dev-1"))
        val session = createValidSession()

        val delegate = object : CommandGateway {
            override suspend fun submitCommand(
                command: TrackerCommand,
                session: Session,
                idempotencyKey: String
            ): CommandResult {
                return CommandResult.Accepted(commandId = "seq-102", deviceId = command.deviceId)
            }
        }

        val gateway = DefaultCommandGateway(
            delegate = delegate,
            scope = testScope,
            defaultTimeoutMs = 5000L
        )

        var finalResult: CommandResult? = null
        val job = launch {
            finalResult = gateway.submitCommand(cmd, session, idKey)
        }
        advanceTimeBy(10)

        val ack = CommandAckEnvelope(
            idempotencyKey = idKey,
            commandId = "seq-102",
            status = "FAILED",
            errorMessage = "Camera sensor busy"
        )
        gateway.handleIncomingAck(ack)
        advanceUntilIdle()

        job.join()
        assertTrue("Result must be Failed", finalResult is CommandResult.Failed)
        val failed = finalResult as CommandResult.Failed
        assertEquals("Camera sensor busy", failed.reason)
        assertFalse("pendingAcks must be cleared after failure", gateway.pendingAcks.containsKey(idKey))
    }

    @Test
    fun `submitCommand falls back gracefully to Accepted when WebSocket ack times out`() = testScope.runTest {
        val idKey = "idemp-ack-timeout"
        val cmd = TrackerCommand.RequestLocation(deviceId = TrackerId("dev-1"))
        val session = createValidSession()

        val delegate = object : CommandGateway {
            override suspend fun submitCommand(
                command: TrackerCommand,
                session: Session,
                idempotencyKey: String
            ): CommandResult {
                return CommandResult.Accepted(commandId = "seq-103", deviceId = command.deviceId)
            }
        }

        val gateway = DefaultCommandGateway(
            delegate = delegate,
            scope = testScope,
            defaultTimeoutMs = 2000L
        )

        var finalResult: CommandResult? = null
        val job = launch {
            finalResult = gateway.submitCommand(cmd, session, idKey)
        }

        advanceTimeBy(2500L)
        advanceUntilIdle()
        job.join()

        assertTrue("Result must fall back gracefully to Accepted on timeout", finalResult is CommandResult.Accepted)
        assertFalse("pendingAcks must be cleaned up on timeout", gateway.pendingAcks.containsKey(idKey))
    }
}
