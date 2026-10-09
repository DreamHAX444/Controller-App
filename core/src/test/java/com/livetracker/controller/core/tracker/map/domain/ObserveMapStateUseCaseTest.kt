package com.livetracker.controller.core.tracker.map.domain

import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.session.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserveMapStateUseCaseTest {

    private class FakeDeviceSelectionManager : DeviceSelectionManager {
        val selectionFlow = MutableStateFlow<DeviceSelection>(DeviceSelection.All)
        override fun getSelection() = selectionFlow
        override fun selectAll() {}
        override fun clearSelection() {}
        override fun selectDevice(deviceId: TrackerId) {}
        override fun selectMultipleDevices(deviceIds: Set<TrackerId>) {}
    }

    private class FakeTrackerDeviceRepository : TrackerDeviceRepository {
        val devicesFlow = MutableStateFlow<List<TrackerDevice>>(emptyList())
        override suspend fun getAllDevices(): List<TrackerDevice> = devicesFlow.value
        override fun observeAllDevices() = devicesFlow
        override fun observeDevice(deviceId: TrackerId) = throw NotImplementedError()
        override suspend fun getDevice(deviceId: TrackerId): TrackerDevice? = throw NotImplementedError()
        override suspend fun registerDevice(device: TrackerDevice): Result<Unit> = throw NotImplementedError()
        override suspend fun unregisterDevice(deviceId: TrackerId): Result<Unit> = throw NotImplementedError()
        override suspend fun updateDevice(device: TrackerDevice): Result<Unit> = throw NotImplementedError()
        override suspend fun updateDevice(deviceId: TrackerId, modifier: (TrackerDevice) -> TrackerDevice): Result<Unit> = throw NotImplementedError()
    }

    private class FakeTimeSource : TimeSource {
        var currentTime = 1000L
        override fun currentTimeMillis() = currentTime
    }

    @Test
    fun `emits empty map state when no devices`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val mapState = useCase().first()
        assertTrue(mapState is MapState.Empty)
    }

    @Test
    fun `ignores devices with unknown location state`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(TrackerId("d1"), "Dev 1", registrationTimestamp = 1000L)
        )
        // No location set, state is UNKNOWN
        
        val mapState = useCase().first() as MapState.Active
        assertTrue(mapState.markers.isEmpty())
    }

    @Test
    fun `maps devices to markers correctly when selection is All`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        val d2 = TrackerId("d2")
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L),
            TrackerDevice(d2, "Dev 2", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(Location(d1, 10.0, 20.0, 5.0f, 90f, 10f, 1000L, LocationState.LIVE))
        locRepo.updateLocation(Location(d2, 30.0, 40.0, null, null, null, 2000L, LocationState.STALE))
        
        selectionManager.selectionFlow.value = DeviceSelection.All
        
        val mapState = useCase().first() as MapState.Active
        assertEquals(2, mapState.markers.size)
        
        val m1 = mapState.markers.find { it.deviceId == d1 }!!
        assertEquals("Dev 1", m1.displayName)
        assertEquals(10.0, m1.latitude, 0.0)
        assertEquals(20.0, m1.longitude, 0.0)
        assertEquals(5.0f, m1.accuracy!!, 0.0f)
        assertEquals(90f, m1.bearing!!, 0.0f)
        assertEquals(1000L, m1.timestamp)
        assertEquals(LocationState.LIVE, m1.locationState)
        assertTrue(m1.isSelected)
        
        val m2 = mapState.markers.find { it.deviceId == d2 }!!
        assertEquals("Dev 2", m2.displayName)
        assertEquals(30.0, m2.latitude, 0.0)
        assertEquals(40.0, m2.longitude, 0.0)
        assertEquals(null, m2.accuracy)
        assertEquals(null, m2.bearing)
        assertEquals(2000L, m2.timestamp)
        assertEquals(LocationState.STALE, m2.locationState)
        assertTrue(m2.isSelected)
    }

    @Test
    fun `marks correctly when selection is Single`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        val d2 = TrackerId("d2")
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L),
            TrackerDevice(d2, "Dev 2", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(Location(d1, 10.0, 20.0, null, null, null, 1000L, LocationState.LIVE))
        locRepo.updateLocation(Location(d2, 30.0, 40.0, null, null, null, 2000L, LocationState.LIVE))
        
        selectionManager.selectionFlow.value = DeviceSelection.Single(d1)
        
        val mapState = useCase().first() as MapState.Active
        assertEquals(2, mapState.markers.size)
        
        val m1 = mapState.markers.find { it.deviceId == d1 }!!
        val m2 = mapState.markers.find { it.deviceId == d2 }!!
        
        assertTrue(m1.isSelected)
        assertFalse(m2.isSelected)
    }
    
    @Test
    fun `marks correctly when selection is Empty`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(Location(d1, 10.0, 20.0, null, null, null, 1000L, LocationState.LIVE))
        
        selectionManager.selectionFlow.value = DeviceSelection.Empty
        
        val mapState = useCase().first() as MapState.Active
        
        assertTrue(mapState.markers.isEmpty())
    }
    
    @Test
    fun `marks correctly when selection is Multiple`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        val d2 = TrackerId("d2")
        val d3 = TrackerId("d3")
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L),
            TrackerDevice(d2, "Dev 2", registrationTimestamp = 1000L),
            TrackerDevice(d3, "Dev 3", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(Location(d1, 10.0, 20.0, null, null, null, 1000L, LocationState.LIVE))
        locRepo.updateLocation(Location(d2, 30.0, 40.0, null, null, null, 2000L, LocationState.LIVE))
        locRepo.updateLocation(Location(d3, 50.0, 60.0, null, null, null, 3000L, LocationState.LIVE))
        
        selectionManager.selectionFlow.value = DeviceSelection.Multiple(setOf(d1, d3))
        
        val mapState = useCase().first() as MapState.Active
        assertEquals(3, mapState.markers.size)
        
        val m1 = mapState.markers.find { it.deviceId == d1 }!!
        val m2 = mapState.markers.find { it.deviceId == d2 }!!
        val m3 = mapState.markers.find { it.deviceId == d3 }!!
        
        assertTrue(m1.isSelected)
        assertFalse(m2.isSelected)
        assertTrue(m3.isSelected)
    }

    @Test
    fun `offline devices are included in map state`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(Location(d1, 10.0, 20.0, 5.0f, null, null, 1000L, LocationState.OFFLINE))
        selectionManager.selectionFlow.value = DeviceSelection.All
        
        val mapState = useCase().first() as MapState.Active
        assertEquals(1, mapState.markers.size)
        assertEquals(LocationState.OFFLINE, mapState.markers.first().locationState)
    }

    @Test
    fun `removed device disappears from map state`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        
        // Add device
        deviceRepo.devicesFlow.value = listOf(TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L))
        locRepo.updateLocation(Location(d1, 10.0, 20.0, 5.0f, null, null, 1000L, LocationState.LIVE))
        selectionManager.selectionFlow.value = DeviceSelection.All
        
        assertEquals(1, (useCase().first() as MapState.Active).markers.size)
        
        // Remove device
        deviceRepo.devicesFlow.value = emptyList()
        
        assertTrue(useCase().first() is MapState.Empty)
    }

    @Test
    fun `location update emits new map state`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val summariesUseCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val selectionManager = FakeDeviceSelectionManager()
        val timeSource = FakeTimeSource()
        
        val useCase = ObserveMapStateUseCase(summariesUseCase, selectionManager, timeSource)
        
        val d1 = TrackerId("d1")
        
        deviceRepo.devicesFlow.value = listOf(TrackerDevice(d1, "Dev 1", registrationTimestamp = 1000L))
        locRepo.updateLocation(Location(d1, 10.0, 20.0, 5.0f, null, null, 1000L, LocationState.LIVE))
        selectionManager.selectionFlow.value = DeviceSelection.All
        
        assertEquals(10.0, (useCase().first() as MapState.Active).markers.first().latitude, 0.0)
        
        // Update location
        locRepo.updateLocation(Location(d1, 50.0, 60.0, 5.0f, null, null, 2000L, LocationState.LIVE))
        
        assertEquals(50.0, (useCase().first() as MapState.Active).markers.first().latitude, 0.0)
    }
}
