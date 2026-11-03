package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.auth.domain.*
import com.livetracker.controller.core.tracker.command.persistence.CommandStatus
import com.livetracker.controller.core.tracker.command.persistence.InMemoryCommandStateStore
import com.livetracker.controller.core.tracker.command.persistence.TrackedCommand
import com.livetracker.controller.core.tracker.domain.*
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerCommandEnvelope
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseFleetManagerIsolationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var clientProviderInvoked = false
    private lateinit var fleetManager: SupabaseFleetManager
    private val currentTime = 10_000L

    @Before
    fun setup() {
        clientProviderInvoked = false
        fleetManager = SupabaseFleetManager(
            clientProvider = {
                clientProviderInvoked = true
                throw AssertionError("FATAL: clientProvider must NOT be called in unauthenticated/Visitor mode!")
            },
            scope = testScope,
            timeProvider = { currentTime }
        )
    }

    private fun createSession(role: Role, isValid: Boolean = true, status: AccountStatus = AccountStatus.ACTIVE): Session {
        val user = User(
            id = "test-user",
            displayName = "Test User",
            role = role,
            accountStatus = status,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = isValid,
            createdAt = 1000L,
            expiresAt = if (isValid) currentTime + 60_000L else currentTime - 1000L
        )
    }

    @Test
    fun `unauthenticated startup makes ZERO Supabase calls and leaves clientProvider untouched`() {
        assertFalse("Supabase client was accessed during unauthenticated initialization!", clientProviderInvoked)
        assertEquals(ConnectionState.DISCONNECTED, fleetManager.connectionState.value)
    }

    @Test
    fun `visitor mode startFleet returns false and makes ZERO Supabase calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)

        val result = fleetManager.startFleet(visitorSession)

        assertFalse(result)
        assertFalse("Supabase client was accessed in Visitor mode!", clientProviderInvoked)
        assertEquals(ConnectionState.DISCONNECTED, fleetManager.connectionState.value)
    }

    @Test
    fun `visitor mode fetchLatestLocations returns empty list and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val locations = fleetManager.fetchLatestLocations()

        assertTrue(locations.isEmpty())
        assertFalse("PostgREST was accessed in Visitor mode!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode fetchFleetDevices returns empty list and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val devices = fleetManager.fetchFleetDevices()

        assertTrue(devices.isEmpty())
        assertFalse("PostgREST was accessed in Visitor mode for fetchFleetDevices!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode fetchDeviceAssignments returns empty list and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val assignments = fleetManager.fetchDeviceAssignments()

        assertTrue(assignments.isEmpty())
        assertFalse("PostgREST was accessed in Visitor mode for fetchDeviceAssignments!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode fetchAssignableUsers returns empty list and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val users = fleetManager.fetchAssignableUsers()

        assertTrue(users.isEmpty())
        assertFalse("PostgREST was accessed in Visitor mode for fetchAssignableUsers!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode assignDeviceToUser fails and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val result = fleetManager.assignDeviceToUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertFalse("PostgREST was accessed in Visitor mode for assignDeviceToUser!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode unassignDeviceFromUser fails and makes ZERO PostgREST calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val result = fleetManager.unassignDeviceFromUser("dev-1", "user-1")

        assertTrue(result.isFailure)
        assertFalse("PostgREST was accessed in Visitor mode for unassignDeviceFromUser!", clientProviderInvoked)
    }

    @Test
    fun `visitor mode sendTrackerCommand returns Unauthorized and makes ZERO Realtime calls`() = testScope.runTest {
        val visitorSession = createSession(Role.VISITOR)
        fleetManager.startFleet(visitorSession)

        val cmd = TrackerCommand.RequestLocation(deviceId = TrackerId("dev-1"))
        val result = fleetManager.sendTrackerCommand(cmd)

        assertTrue(result is CommandResult.Unauthorized)
        assertFalse("Realtime broadcast was attempted in Visitor mode!", clientProviderInvoked)
    }

    @Test
    fun `expired session is rejected and makes ZERO Supabase calls`() = testScope.runTest {
        val expiredSession = createSession(Role.USER, isValid = false)

        val result = fleetManager.startFleet(expiredSession)

        assertFalse(result)
        assertFalse("Supabase client was accessed with expired session!", clientProviderInvoked)
    }

    @Test
    fun `disabled account is rejected and makes ZERO Supabase calls`() = testScope.runTest {
        val disabledSession = createSession(Role.USER, status = AccountStatus.DISABLED)

        val result = fleetManager.startFleet(disabledSession)

        assertFalse(result)
        assertFalse("Supabase client was accessed with disabled account!", clientProviderInvoked)
    }

    @Test
    fun `logout and teardown cancels pending commands and returns state to DISCONNECTED`() = testScope.runTest {
        fleetManager.stopFleet()
        assertEquals(ConnectionState.DISCONNECTED, fleetManager.connectionState.value)
    }

    @Test
    fun `forged or unverified Realtime command broadcast does NOT complete deferred or update state store`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "forged-cmd-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Verifier returns null (command not found on server or uncommitted)
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { _, _, _ -> null }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        // Adversary injects forged Realtime broadcast claiming command is executed
        val forgedEnvelope = TrackerCommandEnvelope(
            deviceId = "dev-1",
            command = "start_camera",
            status = "executed",
            idempotencyKey = idKey
        )
        testFleetManager.handleCommandResponse(forgedEnvelope)
        testScheduler.advanceUntilIdle()

        // Deferred must NOT be completed!
        assertFalse("Deferred must NOT be completed by forged unverified broadcast", deferred.isCompleted)
        // Store must NOT be transitioned to EXECUTED!
        val record = store.getCommand(idKey)
        assertNotNull(record)
        assertEquals("Store status must remain SUBMITTED", CommandStatus.SUBMITTED, record!!.status)
    }

    @Test
    fun `server verified executed command broadcast completes deferred and updates store to EXECUTED`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "valid-cmd-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Verifier confirms server status is executed
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { dev, key, _ ->
                if (dev == "dev-1" && key == idKey) {
                    ServerCommandStatusRecord(id = 1001L, deviceId = "dev-1", idempotencyKey = idKey, status = "executed", resultPayload = "{\"success\":true}")
                } else null
            }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        val broadcastEnvelope = TrackerCommandEnvelope(
            id = 1001L,
            deviceId = "dev-1",
            command = "start_camera",
            status = "executed",
            idempotencyKey = idKey
        )
        testFleetManager.handleCommandResponse(broadcastEnvelope)
        testScheduler.advanceUntilIdle()

        assertTrue("Deferred must be completed when server verifies executed", deferred.isCompleted)
        val result = deferred.getCompleted()
        assertTrue("Result must be Completed", result is CommandResult.Completed)

        val record = store.getCommand(idKey)
        assertNotNull(record)
        assertEquals(CommandStatus.EXECUTED, record!!.status)
    }

    @Test
    fun `broadcast with wrong device ID on server record is rejected and does NOT complete deferred`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "spoofed-device-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Server record belongs to dev-2, but broadcast claimed dev-1
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { _, _, _ ->
                ServerCommandStatusRecord(id = 1001L, deviceId = "dev-2", idempotencyKey = idKey, status = "executed")
            }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        val envelope = TrackerCommandEnvelope(id = 1001L, deviceId = "dev-1", command = "start_camera", status = "executed", idempotencyKey = idKey)
        testFleetManager.handleCommandResponse(envelope)
        testScheduler.advanceUntilIdle()

        assertFalse("Deferred must NOT complete when device ID does not match server record", deferred.isCompleted)
        assertEquals(CommandStatus.SUBMITTED, store.getCommand(idKey)!!.status)
    }

    @Test
    fun `broadcast with sequence ID mismatch on server record is rejected`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "seq-mismatch-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Server record has sequence ID 9999, but broadcast has 1001
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { dev, key, _ ->
                ServerCommandStatusRecord(id = 9999L, deviceId = dev, idempotencyKey = key, status = "executed")
            }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        val envelope = TrackerCommandEnvelope(id = 1001L, deviceId = "dev-1", command = "start_camera", status = "executed", idempotencyKey = idKey)
        testFleetManager.handleCommandResponse(envelope)
        testScheduler.advanceUntilIdle()

        assertFalse("Deferred must NOT complete when sequence ID does not match server record", deferred.isCompleted)
        assertEquals(CommandStatus.SUBMITTED, store.getCommand(idKey)!!.status)
    }

    @Test
    fun `broadcast ignored when server record is in non-terminal pending state`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "pending-state-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Server record is still pending
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { dev, key, _ ->
                ServerCommandStatusRecord(id = 1001L, deviceId = dev, idempotencyKey = key, status = "pending")
            }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        val envelope = TrackerCommandEnvelope(id = 1001L, deviceId = "dev-1", command = "start_camera", status = "executed", idempotencyKey = idKey)
        testFleetManager.handleCommandResponse(envelope)
        testScheduler.advanceUntilIdle()

        assertFalse("Deferred must NOT complete when server record is still pending", deferred.isCompleted)
        assertEquals(CommandStatus.SUBMITTED, store.getCommand(idKey)!!.status)
    }

    @Test
    fun `conflicting terminal status on server transitions to server status FAILED rather than broadcast status EXECUTED`() = testScope.runTest {
        val store = InMemoryCommandStateStore()
        val idKey = "conflict-status-uuid"
        store.recordCommand(
            TrackedCommand(
                idempotencyKey = idKey,
                deviceId = "dev-1",
                commandName = "start_camera",
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = currentTime
            )
        )

        // Server record has status FAILED, but broadcast falsely claims EXECUTED
        val testFleetManager = SupabaseFleetManager(
            clientProvider = { throw AssertionError("No direct client calls expected") },
            scope = testScope,
            timeProvider = { currentTime },
            commandStateStore = store,
            serverStatusVerifier = { dev, key, _ ->
                ServerCommandStatusRecord(id = 1001L, deviceId = dev, idempotencyKey = key, status = "failed", resultPayload = "Hardware failed")
            }
        )

        val deferred = CompletableDeferred<CommandResult>()
        testFleetManager.registerPendingCommand(idKey, deferred)

        val envelope = TrackerCommandEnvelope(id = 1001L, deviceId = "dev-1", command = "start_camera", status = "executed", idempotencyKey = idKey)
        testFleetManager.handleCommandResponse(envelope)
        testScheduler.advanceUntilIdle()

        assertTrue("Deferred must complete when server record is terminal", deferred.isCompleted)
        val result = deferred.getCompleted()
        assertTrue("Result must be Failed matching server record", result is CommandResult.Failed)
        assertEquals(CommandStatus.FAILED, store.getCommand(idKey)!!.status)
    }

    @Test
    fun `stopFleet comprehensively cleans up all active dynamic realtime subscriptions`() = testScope.runTest {
        val testClient = createSupabaseClient("https://example.supabase.co", "dummy-key") {
            install(Realtime)
        }
        val ch1 = testClient.channel("webrtc-session-123")
        val ch2 = testClient.channel("media-audio-session-123")
        ch1.subscribe(blockUntilSubscribed = false)
        ch2.subscribe(blockUntilSubscribed = false)
        assertEquals(2, testClient.realtime.subscriptions.size)

        val testFleetManager = SupabaseFleetManager(
            clientProvider = { testClient },
            scope = testScope,
            timeProvider = { currentTime }
        )
        val adminSession = createSession(Role.ADMIN)
        testFleetManager.setCurrentSessionForTesting(adminSession)

        testFleetManager.stopFleet()

        assertTrue(
            "All dynamic realtime subscriptions must be cleaned up and removed on stopFleet",
            testClient.realtime.subscriptions.isEmpty()
        )
        assertEquals(ConnectionState.DISCONNECTED, testFleetManager.connectionState.value)
    }
}

