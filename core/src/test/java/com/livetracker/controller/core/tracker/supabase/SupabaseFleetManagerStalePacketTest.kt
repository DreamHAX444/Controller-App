package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.location.data.LocationData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseFleetManagerStalePacketTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createAdminSession(): Session {
        val now = System.currentTimeMillis()
        val user = User(
            id = "admin-user",
            displayName = "Admin",
            role = Role.ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = true,
            createdAt = now,
            expiresAt = now + 3600_000L
        )
    }

    private class TestableFleetManager(scope: kotlinx.coroutines.CoroutineScope) : SupabaseFleetManager(
        clientProvider = { throw AssertionError("No direct client calls expected") },
        scope = scope
    ) {
        override suspend fun startFleet(session: Session, currentTime: Long): Boolean {
            currentSession = session
            setAuthorizedDevices(listOf("tracker-needle-1"))
            return true
        }

        override suspend fun registerDeviceInCloud(deviceRecord: DeviceRecord): Result<Unit> {
            return Result.success(Unit)
        }
    }

    @Test
    fun `stale-packet shield drops packets with timestamp less than or equal to lastReceivedTimestamp`() = testScope.runTest {
        val fleetManager = TestableFleetManager(testScope)
        fleetManager.startFleet(createAdminSession())

        val receivedEvents = mutableListOf<TrackerEvent.LocationUpdated>()
        val collectJob = launch {
            fleetManager.locationEvents.collect {
                receivedEvents.add(it)
            }
        }
        testScheduler.runCurrent()

        // 1. Packet 1: timestamp = 10000 -> Accepted
        val packet1 = LocationData(
            deviceId = "tracker-needle-1",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 5.0f,
            createdAt = "2026-10-30T10:00:10.000Z"
        )
        val ts1 = packet1.parseTimestamp()
        fleetManager.handleIncomingLocation(packet1)
        advanceUntilIdle()

        assertEquals(1, receivedEvents.size)
        assertEquals(ts1, fleetManager.getLastReceivedTimestamp("tracker-needle-1"))

        // 2. Packet 2: Stale packet with timestamp < ts1 -> Dropped
        val stalePacket = LocationData(
            deviceId = "tracker-needle-1",
            latitude = 37.7740,
            longitude = -122.4180,
            accuracy = 5.0f,
            createdAt = "2026-10-30T10:00:05.000Z" // 5 seconds earlier
        )
        fleetManager.handleIncomingLocation(stalePacket)
        advanceUntilIdle()

        // Size must still be 1 (needle did NOT jump backwards)
        assertEquals("Stale packet must be dropped", 1, receivedEvents.size)
        assertEquals("lastReceivedTimestamp must remain at ts1", ts1, fleetManager.getLastReceivedTimestamp("tracker-needle-1"))

        // 3. Packet 3: Duplicate packet with timestamp == ts1 -> Dropped
        val duplicatePacket = LocationData(
            deviceId = "tracker-needle-1",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 5.0f,
            createdAt = "2026-10-30T10:00:10.000Z"
        )
        fleetManager.handleIncomingLocation(duplicatePacket)
        advanceUntilIdle()

        assertEquals("Duplicate timestamp packet must be dropped", 1, receivedEvents.size)

        // 4. Packet 4: Fresh packet with timestamp > ts1 -> Accepted
        val freshPacket = LocationData(
            deviceId = "tracker-needle-1",
            latitude = 37.7755,
            longitude = -122.4200,
            accuracy = 4.0f,
            createdAt = "2026-10-30T10:00:15.000Z" // 5 seconds later
        )
        val ts4 = freshPacket.parseTimestamp()
        fleetManager.handleIncomingLocation(freshPacket)
        advanceUntilIdle()

        assertEquals(2, receivedEvents.size)
        assertEquals("lastReceivedTimestamp must update to ts4", ts4, fleetManager.getLastReceivedTimestamp("tracker-needle-1"))
        assertEquals(37.7755, receivedEvents[1].latitude, 0.0001)

        collectJob.cancel()
    }
}
