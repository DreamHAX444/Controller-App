package com.livetracker.controller.auth.pin

import com.livetracker.controller.core.security.PinManager
import com.livetracker.controller.core.security.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PinViewModelTest {

    private lateinit var fakeStorage: SecureStorage
    private lateinit var pinManager: PinManager
    private lateinit var viewModel: PinViewModel

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeStorage = FakeSecureStorage()
        pinManager = PinManager(fakeStorage)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStatePinNotSet() {
        viewModel = PinViewModel(pinManager)
        assertTrue(viewModel.authState.value is AuthState.PinNotConfigured)
    }

    @Test
    fun testSetupPinFlow() {
        viewModel = PinViewModel(pinManager)
        viewModel.submitPin("1234")
        assertTrue(viewModel.authState.value is AuthState.SetupConfirm)
        
        viewModel.submitPin("1234")
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        assertTrue(pinManager.isPinSet())
    }
}

class FakeSecureStorage : SecureStorage {
    private val map = mutableMapOf<String, Any>()

    override fun getString(key: String, defaultValue: String?): String? = map[key] as? String ?: defaultValue
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue
    override fun putInt(key: String, value: Int) { map[key] = value }

    override fun getLong(key: String, defaultValue: Long): Long = map[key] as? Long ?: defaultValue
    override fun putLong(key: String, value: Long) { map[key] = value }

    override fun clear() { map.clear() }
}
