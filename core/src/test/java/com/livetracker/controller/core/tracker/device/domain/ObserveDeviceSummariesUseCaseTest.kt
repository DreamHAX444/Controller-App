package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ObserveDeviceSummariesUseCaseTest {

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

    @Test
    fun `emits empty list when no devices`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val useCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val summaries = useCase().first()
        assertEquals(0, summaries.size)
    }

    @Test
    fun `combines device without location`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val useCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(TrackerId("d1"), "Dev 1", registrationTimestamp = 1000L)
        )
        
        val summaries = useCase().first()
        assertEquals(1, summaries.size)
        val summary = summaries[0]
        assertEquals("Dev 1", summary.device.displayName)
        assertNull(summary.latestLocation)
        assertEquals(LocationState.UNKNOWN, summary.locationState)
        assertEquals(false, summary.isStale)
    }

    @Test
    fun `combines device with location`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val useCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val id = TrackerId("d1")
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(id, "Dev 1", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(
            Location(id, 10.0, 20.0, 5.0f, null, null, 2000L, LocationState.LIVE)
        )
        
        val summaries = useCase().first()
        assertEquals(1, summaries.size)
        val summary = summaries[0]
        assertNotNull(summary.latestLocation)
        assertEquals(10.0, summary.latestLocation?.latitude!!, 0.0)
        assertEquals(LocationState.LIVE, summary.locationState)
        assertEquals(2000L, summary.locationTimestamp)
        assertEquals(false, summary.isStale)
    }

    @Test
    fun `combines device with stale location`() = runTest {
        val deviceRepo = FakeTrackerDeviceRepository()
        val locRepo = InMemoryTrackerLocationRepository()
        val useCase = ObserveDeviceSummariesUseCase(deviceRepo, locRepo)
        
        val id = TrackerId("d1")
        deviceRepo.devicesFlow.value = listOf(
            TrackerDevice(id, "Dev 1", registrationTimestamp = 1000L)
        )
        
        locRepo.updateLocation(
            Location(id, 10.0, 20.0, 5.0f, null, null, 2000L, LocationState.STALE)
        )
        
        val summaries = useCase().first()
        assertEquals(1, summaries.size)
        val summary = summaries[0]
        assertEquals(LocationState.STALE, summary.locationState)
        assertEquals(true, summary.isStale)
    }
}
