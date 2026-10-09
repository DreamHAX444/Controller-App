package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.device.domain.DeviceHealth
import com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.domain.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryTrackerDeviceRepositoryTest {

    private lateinit var repository: InMemoryTrackerDeviceRepository
    private val deviceId1 = TrackerId("DEV1")
    private val deviceId2 = TrackerId("DEV2")

    @Before
    fun setup() {
        repository = InMemoryTrackerDeviceRepository()
    }

    @Test
    fun `registerDevice adds new device to repository`() = runTest {
        val device = TrackerDevice(deviceId1, "Test Device", registrationTimestamp = 1000L)
        val result = repository.registerDevice(device)
        assertTrue(result.isSuccess)
        
        val retrieved = repository.getDevice(deviceId1)
        assertNotNull(retrieved)
        assertEquals(deviceId1, retrieved?.deviceId)
        assertEquals("Test Device", retrieved?.displayName)
    }

    @Test
    fun `registerDevice fails if device already exists`() = runTest {
        val device = TrackerDevice(deviceId1, "First Name", registrationTimestamp = 1000L)
        repository.registerDevice(device)
        
        val result = repository.registerDevice(TrackerDevice(deviceId1, "Second Name", registrationTimestamp = 1000L))
        assertTrue(result.isFailure)
        
        val retrieved = repository.getDevice(deviceId1)
        assertEquals("First Name", retrieved?.displayName)
    }

    @Test
    fun `updateDevice replaces device`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Test Device", registrationTimestamp = 1000L))
        
        val updated = TrackerDevice(deviceId1, "New Name", registrationTimestamp = 1000L)
        val result = repository.updateDevice(updated)
        assertTrue(result.isSuccess)
        
        val retrieved = repository.getDevice(deviceId1)
        assertEquals("New Name", retrieved?.displayName)
    }
    
    @Test
    fun `updateDevice modifier updates device attributes`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Test Device", registrationTimestamp = 1000L))
        
        val result = repository.updateDevice(deviceId1) { it.copy(lifecycle = DeviceLifecycle.ACTIVE) }
        assertTrue(result.isSuccess)
        
        val retrieved = repository.getDevice(deviceId1)
        assertEquals(DeviceLifecycle.ACTIVE, retrieved?.lifecycle)
    }
    
    @Test
    fun `updateDevice modifier fails if device id is modified`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Test Device", registrationTimestamp = 1000L))
        
        val result = repository.updateDevice(deviceId1) { it.copy(deviceId = TrackerId("OTHER")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `updateDevice modifier fails if device does not exist`() = runTest {
        val result = repository.updateDevice(deviceId1) { it.copy(lifecycle = DeviceLifecycle.ACTIVE) }
        assertTrue(result.isFailure)
        assertNull(repository.getDevice(deviceId1))
    }

    @Test
    fun `unregisterDevice removes device from repository`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Test Device", registrationTimestamp = 1000L))
        
        val result = repository.unregisterDevice(deviceId1)
        assertTrue(result.isSuccess)
        
        assertNull(repository.getDevice(deviceId1))
    }

    @Test
    fun `getAllDevices returns all added devices`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Device 1", registrationTimestamp = 1000L))
        repository.registerDevice(TrackerDevice(deviceId2, "Device 2", registrationTimestamp = 1000L))
        
        val devices = repository.getAllDevices()
        assertEquals(2, devices.size)
        assertEquals("Device 1", devices.find { it.deviceId == deviceId1 }?.displayName)
    }

    @Test
    fun `observeDevice emits current and new state`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Device 1", registrationTimestamp = 1000L))
        
        val flow = repository.observeDevice(deviceId1)
        var device = flow.first()
        assertEquals("Device 1", device?.displayName)
        
        repository.updateDevice(deviceId1) { it.copy(displayName = "Updated 1") }
        device = flow.first()
        assertEquals("Updated 1", device?.displayName)
    }

    @Test
    fun `observeAllDevices emits current and new devices`() = runTest {
        repository.registerDevice(TrackerDevice(deviceId1, "Device 1", registrationTimestamp = 1000L))
        
        val flow = repository.observeAllDevices()
        var devices = flow.first()
        assertEquals(1, devices.size)
        
        repository.registerDevice(TrackerDevice(deviceId2, "Device 2", registrationTimestamp = 1000L))
        devices = flow.first()
        assertEquals(2, devices.size)
    }
}
