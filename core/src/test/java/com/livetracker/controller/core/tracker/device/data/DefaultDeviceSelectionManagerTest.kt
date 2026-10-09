package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultDeviceSelectionManagerTest {

    @Test
    fun `initial state is Empty`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Empty, selection)
    }

    @Test
    fun `selectDevice updates state to Single`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        val deviceId = TrackerId("DEV1")
        
        manager.selectDevice(deviceId)
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Single(deviceId), selection)
    }

    @Test
    fun `selectMultipleDevices with empty set updates state to Empty`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        manager.selectDevice(TrackerId("DEV1")) // Set initial non-empty
        
        manager.selectMultipleDevices(emptySet())
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Empty, selection)
    }

    @Test
    fun `selectMultipleDevices with single element updates state to Single`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        val deviceId = TrackerId("DEV1")
        
        manager.selectMultipleDevices(setOf(deviceId))
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Single(deviceId), selection)
    }

    @Test
    fun `selectMultipleDevices with multiple elements updates state to Multiple`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        val deviceIds = setOf(TrackerId("DEV1"), TrackerId("DEV2"))
        
        manager.selectMultipleDevices(deviceIds)
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Multiple(deviceIds), selection)
    }

    @Test
    fun `selectAll updates state to All`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        
        manager.selectAll()
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.All, selection)
    }

    @Test
    fun `clearSelection updates state to Empty`() = runTest {
        val manager = DefaultDeviceSelectionManager()
        manager.selectAll()
        
        manager.clearSelection()
        
        val selection = manager.getSelection().first()
        assertEquals(DeviceSelection.Empty, selection)
    }
}
