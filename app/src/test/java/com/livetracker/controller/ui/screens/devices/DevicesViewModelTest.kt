package com.livetracker.controller.ui.screens.devices

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.device.data.DefaultDeviceSelectionManager
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.DeviceRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseFleetManager
import com.livetracker.controller.core.tracker.supabase.UserProfileRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSessionRepository(initialSession: Session? = null) : SessionRepository {
        private val _session = MutableStateFlow(initialSession)

        override suspend fun createSession(session: Session): Result<Unit> {
            _session.value = session
            return Result.success(Unit)
        }

        override suspend fun getActiveSession(): Result<Session> {
            val curr = _session.value ?: return Result.failure(IllegalStateException("No active session"))
            return Result.success(curr)
        }

        override suspend fun updateSessionUser(user: com.livetracker.controller.core.auth.domain.User): Result<Unit> {
            val curr = _session.value ?: return Result.failure(IllegalStateException("No active session"))
            _session.value = curr.copy(user = user)
            return Result.success(Unit)
        }

        override suspend fun invalidateSession(): Result<Unit> {
            _session.value = null
            return Result.success(Unit)
        }

        override fun observeActiveSession(): Flow<Session?> = _session.asStateFlow()

        fun setSession(session: Session?) {
            _session.value = session
        }
    }

    private class FakeSupabaseFleetManager(
        initialAssignments: List<DeviceAssignmentRecord> = emptyList(),
        initialUsers: List<UserProfileRecord> = emptyList()
    ) : SupabaseFleetManager(
        clientProvider = { throw UnsupportedOperationException("No real Supabase client in unit tests") },
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
    ) {
        val assignments = initialAssignments.toMutableList()
        val users = initialUsers.toMutableList()
        var lastAssignedDevice: String? = null
        var lastAssignedUser: String? = null
        var lastUnassignedDevice: String? = null
        var lastUnassignedUser: String? = null

        override suspend fun fetchDeviceAssignments(): List<DeviceAssignmentRecord> = assignments.toList()

        override suspend fun fetchAssignableUsers(): List<UserProfileRecord> = users.toList()

        override suspend fun assignDeviceToUser(deviceId: String, userId: String, expiresAt: String?): Result<Unit> {
            lastAssignedDevice = deviceId
            lastAssignedUser = userId
            assignments.removeAll { it.deviceId == deviceId }
            assignments.add(DeviceAssignmentRecord(userId = userId, deviceId = deviceId, status = "ACTIVE"))
            return Result.success(Unit)
        }

        override suspend fun unassignDeviceFromUser(deviceId: String, userId: String): Result<Unit> {
            lastUnassignedDevice = deviceId
            lastUnassignedUser = userId
            assignments.removeAll { it.deviceId == deviceId && it.userId == userId }
            return Result.success(Unit)
        }

        var lastSentCommand: com.livetracker.controller.core.tracker.domain.TrackerCommand? = null
        override suspend fun sendTrackerCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand): com.livetracker.controller.core.tracker.domain.CommandResult {
            lastSentCommand = command
            return com.livetracker.controller.core.tracker.domain.CommandResult.Accepted(command.commandId, command.deviceId)
        }
    }

    private lateinit var deviceRepository: InMemoryTrackerDeviceRepository
    private lateinit var locationRepository: InMemoryTrackerLocationRepository
    private lateinit var observeDeviceSummariesUseCase: ObserveDeviceSummariesUseCase
    private lateinit var deviceSelectionManager: DefaultDeviceSelectionManager
    private lateinit var sessionRepository: FakeSessionRepository
    private lateinit var fakeFleetManager: FakeSupabaseFleetManager

    private fun createSession(role: Role): Session {
        val user = User(
            id = "test-user-id",
            displayName = "Admin User",
            role = role,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        return Session(
            sessionId = "sess-1",
            user = user,
            isAuthenticated = true,
            createdAt = 1000L,
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        deviceRepository = InMemoryTrackerDeviceRepository()
        locationRepository = InMemoryTrackerLocationRepository()
        observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(deviceRepository, locationRepository)
        deviceSelectionManager = DefaultDeviceSelectionManager()
        sessionRepository = FakeSessionRepository(createSession(Role.ADMIN))
        fakeFleetManager = FakeSupabaseFleetManager(
            initialAssignments = listOf(
                DeviceAssignmentRecord(userId = "user-123", deviceId = "dev-assigned", status = "ACTIVE")
            ),
            initialUsers = listOf(
                UserProfileRecord(id = "user-123", email = "u1@test.com", displayName = "User One", role = "USER", status = "ACTIVE"),
                UserProfileRecord(id = "user-456", email = "u2@test.com", displayName = "User Two", role = "USER", status = "ACTIVE")
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state maps device summaries with correct assigned and unassigned counts`() = runTest {
        // Register two devices: one assigned, one unassigned
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-assigned"),
                displayName = "Tracker Assigned",
                deviceModel = "Pixel 10 Pro",
                registrationTimestamp = 1000L
            )
        )
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-unassigned"),
                displayName = "Tracker Unassigned",
                deviceModel = "Galaxy S24",
                registrationTimestamp = 2000L
            )
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager,
            timeProvider = { 3000L }
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.totalCount)
        assertEquals(1, state.assignedCount)
        assertEquals(1, state.unassignedCount)
        assertEquals(2, state.devices.size)

        val assignedItem = state.devices.find { it.deviceId == "dev-assigned" }
        assertNotNull(assignedItem)
        assertTrue(assignedItem!!.isAssigned)
        assertEquals("user-123", assignedItem.assignedUserId)

        val unassignedItem = state.devices.find { it.deviceId == "dev-unassigned" }
        assertNotNull(unassignedItem)
        assertFalse(unassignedItem!!.isAssigned)
        assertNull(unassignedItem.assignedUserId)

        collectJob.cancel()
    }

    @Test
    fun `search query filters devices by name, id, and model`() = runTest {
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-pixel"),
                displayName = "Alpha Tracker",
                deviceModel = "Pixel 10 Pro",
                registrationTimestamp = 1000L
            )
        )
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-samsung"),
                displayName = "Beta Tracker",
                deviceModel = "Galaxy S24",
                registrationTimestamp = 2000L
            )
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        // Search by model "Pixel"
        viewModel.onSearchQueryChanged("Pixel")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.devices.size)
        assertEquals("dev-pixel", viewModel.uiState.value.devices.first().deviceId)

        // Search by name "Beta"
        viewModel.onSearchQueryChanged("Beta")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.devices.size)
        assertEquals("dev-samsung", viewModel.uiState.value.devices.first().deviceId)

        // Clear query
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.devices.size)

        collectJob.cancel()
    }

    @Test
    fun `tab selection filters devices between ALL, ASSIGNED, UNASSIGNED`() = runTest {
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-assigned"),
                displayName = "Assigned Phone",
                registrationTimestamp = 1000L
            )
        )
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-unassigned"),
                displayName = "Unassigned Phone",
                registrationTimestamp = 2000L
            )
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        // Tab: ASSIGNED
        viewModel.onTabSelected(DeviceFilterTab.ASSIGNED)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.devices.size)
        assertEquals("dev-assigned", viewModel.uiState.value.devices.first().deviceId)

        // Tab: UNASSIGNED
        viewModel.onTabSelected(DeviceFilterTab.UNASSIGNED)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.devices.size)
        assertEquals("dev-unassigned", viewModel.uiState.value.devices.first().deviceId)

        // Tab: ALL
        viewModel.onTabSelected(DeviceFilterTab.ALL)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.devices.size)

        collectJob.cancel()
    }

    @Test
    fun `selectDevice delegates to deviceSelectionManager`() = runTest {
        deviceRepository.registerDevice(
            TrackerDevice(deviceId = TrackerId("dev-test"), displayName = "Test Phone", registrationTimestamp = 1000L)
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onSelectDevice("dev-test")
        advanceUntilIdle()

        val selection = deviceSelectionManager.getSelection().value
        assertTrue(selection is DeviceSelection.Single)
        assertEquals(TrackerId("dev-test"), (selection as DeviceSelection.Single).deviceId)

        assertTrue(viewModel.uiState.value.devices.first().isSelected)

        collectJob.cancel()
    }

    @Test
    fun `assignDevice assigns unassigned device to user and reloads assignments`() = runTest {
        deviceRepository.registerDevice(
            TrackerDevice(deviceId = TrackerId("dev-unassigned"), displayName = "Unassigned Phone", registrationTimestamp = 1000L)
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.onOpenAssignmentDialog("dev-unassigned")
        advanceUntilIdle()
        assertEquals("dev-unassigned", viewModel.uiState.value.selectedDeviceIdForAssignment)

        viewModel.assignDevice("dev-unassigned", "user-456")
        advanceUntilIdle()

        assertEquals("dev-unassigned", fakeFleetManager.lastAssignedDevice)
        assertEquals("user-456", fakeFleetManager.lastAssignedUser)
        assertNull(viewModel.uiState.value.selectedDeviceIdForAssignment)
        assertNotNull(viewModel.uiState.value.statusMessage)
        assertTrue(viewModel.uiState.value.statusMessage!!.contains("assigned successfully"))

        val item = viewModel.uiState.value.devices.find { it.deviceId == "dev-unassigned" }
        assertNotNull(item)
        assertTrue(item!!.isAssigned)

        collectJob.cancel()
    }

    @Test
    fun `unassignDevice revokes assignment and reloads assignments`() = runTest {
        deviceRepository.registerDevice(
            TrackerDevice(deviceId = TrackerId("dev-assigned"), displayName = "Assigned Phone", registrationTimestamp = 1000L)
        )

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.unassignDevice("dev-assigned", "user-123")
        advanceUntilIdle()

        assertEquals("dev-assigned", fakeFleetManager.lastUnassignedDevice)
        assertEquals("user-123", fakeFleetManager.lastUnassignedUser)
        assertNotNull(viewModel.uiState.value.statusMessage)

        val item = viewModel.uiState.value.devices.find { it.deviceId == "dev-assigned" }
        assertNotNull(item)
        assertFalse(item!!.isAssigned)

        collectJob.cancel()
    }

    @Test
    fun `assignDevice rejected when user is regular user`() = runTest {
        sessionRepository.setSession(createSession(Role.USER))

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.assignDevice("dev-1", "user-1")
        advanceUntilIdle()

        assertNull(fakeFleetManager.lastAssignedDevice)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Only Admins and Owners"))

        collectJob.cancel()
    }

    @Test
    fun `visitor mode sets isVisitor flag and isolates physical devices`() = runTest {
        sessionRepository.setSession(createSession(Role.VISITOR))

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isVisitor)
        assertEquals(Role.VISITOR, viewModel.uiState.value.userRole)
        assertTrue(viewModel.uiState.value.devices.isEmpty())
        assertEquals(0, viewModel.uiState.value.totalCount)

        collectJob.cancel()
    }

    @Test
    fun `regular user only sees assigned devices and unassigned are hidden`() = runTest {
        val userWithAssignment = User(
            id = "test-user-id",
            displayName = "Regular User",
            role = Role.USER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = listOf(
                com.livetracker.controller.core.auth.domain.DevicePermission(
                    deviceId = "dev-assigned",
                    allowedServices = setOf(com.livetracker.controller.core.auth.domain.ServicePermission.LOCATION),
                    allowedActions = setOf(com.livetracker.controller.core.auth.domain.Action.READ)
                )
            )
        )
        val userSession = Session(
            sessionId = "sess-user",
            user = userWithAssignment,
            isAuthenticated = true,
            createdAt = 1000L,
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-assigned"),
                displayName = "Tracker Assigned",
                deviceModel = "Pixel 10 Pro",
                registrationTimestamp = 1000L
            )
        )
        deviceRepository.registerDevice(
            TrackerDevice(
                deviceId = TrackerId("dev-unassigned"),
                displayName = "Tracker Unassigned",
                deviceModel = "Galaxy S24",
                registrationTimestamp = 2000L
            )
        )

        sessionRepository.setSession(userSession)

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(Role.USER, state.userRole)
        assertEquals(1, state.totalCount)
        assertEquals(1, state.devices.size)
        assertEquals("dev-assigned", state.devices[0].deviceId)
        assertEquals(0, state.unassignedCount)

        collectJob.cancel()
    }

    @Test
    fun wakeDevice_asAdmin_sendsWakeCommandSuccessfully() = runTest(testDispatcher) {
        sessionRepository.setSession(createSession(Role.ADMIN))

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.wakeDevice("dev-1")
        advanceUntilIdle()

        val lastCmd = fakeFleetManager.lastSentCommand
        assertNotNull(lastCmd)
        assertTrue("Expected Wake command but was $lastCmd", lastCmd is com.livetracker.controller.core.tracker.domain.TrackerCommand.Wake)
        assertEquals("dev-1", lastCmd?.deviceId?.value)
        assertEquals("Wake command sent to dev-1", viewModel.uiState.value.statusMessage)

        collectJob.cancel()
    }

    @Test
    fun sleepDevice_asAdmin_sendsSleepCommandSuccessfully() = runTest(testDispatcher) {
        sessionRepository.setSession(createSession(Role.ADMIN))

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.sleepDevice("dev-1")
        advanceUntilIdle()

        val lastCmd = fakeFleetManager.lastSentCommand
        assertNotNull(lastCmd)
        assertTrue("Expected Sleep command but was $lastCmd", lastCmd is com.livetracker.controller.core.tracker.domain.TrackerCommand.Sleep)
        assertEquals("dev-1", lastCmd?.deviceId?.value)
        assertEquals("Sleep command sent to dev-1", viewModel.uiState.value.statusMessage)

        collectJob.cancel()
    }

    @Test
    fun wakeDevice_asVisitor_failsClosed() = runTest(testDispatcher) {
        sessionRepository.setSession(createSession(Role.VISITOR))

        val viewModel = DevicesViewModel(
            observeDeviceSummariesUseCase = observeDeviceSummariesUseCase,
            deviceSelectionManager = deviceSelectionManager,
            sessionRepository = sessionRepository,
            supabaseFleetManager = fakeFleetManager
        )

        val collectJob = launch(testDispatcher) { viewModel.uiState.collect() }
        advanceUntilIdle()

        viewModel.wakeDevice("dev-1")
        advanceUntilIdle()

        assertNull("Visitor must not be allowed to send wake command", fakeFleetManager.lastSentCommand)
        assertEquals("Unauthorized: visitors cannot control devices", viewModel.uiState.value.errorMessage)

        collectJob.cancel()
    }
}
