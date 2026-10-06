package com.livetracker.controller.auth.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.security.PinManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    object Initializing : AuthState()
    object PinNotConfigured : AuthState()
    object SetupConfirm : AuthState()
    object Locked : AuthState()
    object Verifying : AuthState()
    object Authenticated : AuthState()
    data class Error(val message: String, val lockoutTimeMs: Long = 0) : AuthState()
}

class PinViewModel(private val pinManager: PinManager) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var pendingSetupPin: String? = null
    
    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        if (!pinManager.isPinSet()) {
            _authState.value = AuthState.PinNotConfigured
        } else if (pinManager.isLockedOut()) {
            val lockout = pinManager.getRemainingLockoutTimeMs()
            _authState.value = AuthState.Error("Too many attempts. Try again later.", lockout)
            startLockoutTimer(lockout)
        } else {
            _authState.value = AuthState.Locked
        }
    }

    fun submitPin(pin: String) {
        when (_authState.value) {
            is AuthState.PinNotConfigured -> handleSetupPhase1(pin)
            is AuthState.SetupConfirm -> handleSetupConfirm(pin)
            is AuthState.Locked, is AuthState.Error -> handleVerify(pin)
            else -> {}
        }
    }

    private fun handleSetupPhase1(pin: String) {
        if (pin.length == 4) {
            pendingSetupPin = pin
            _authState.value = AuthState.SetupConfirm
        }
    }

    private fun handleSetupConfirm(pin: String) {
        if (pin == pendingSetupPin) {
            _authState.value = AuthState.Verifying
            try {
                pinManager.setPin(pin)
                _authState.value = AuthState.Authenticated
            } catch (e: Exception) {
                _authState.value = AuthState.Error("Failed to save PIN")
            }
        } else {
            pendingSetupPin = null
            _authState.value = AuthState.Error("PINs do not match.")
            viewModelScope.launch {
                delay(1500)
                _authState.value = AuthState.PinNotConfigured
            }
        }
    }

    private fun handleVerify(pin: String) {
        if (pinManager.isLockedOut()) {
            val lockout = pinManager.getRemainingLockoutTimeMs()
            _authState.value = AuthState.Error("Too many attempts.", lockout)
            return
        }

        _authState.value = AuthState.Verifying
        
        viewModelScope.launch {
            delay(300) // Slight delay to prevent rapid brute-forcing
            if (pinManager.verifyPin(pin)) {
                _authState.value = AuthState.Authenticated
            } else {
                if (pinManager.isLockedOut()) {
                    val lockoutTime = pinManager.getRemainingLockoutTimeMs()
                    _authState.value = AuthState.Error("Too many incorrect attempts.", lockoutTime)
                    startLockoutTimer(lockoutTime)
                } else {
                    _authState.value = AuthState.Error("Incorrect PIN")
                    delay(1000)
                    if (_authState.value !is AuthState.Authenticated) {
                        _authState.value = AuthState.Locked
                    }
                }
            }
        }
    }

    private fun startLockoutTimer(durationMs: Long) {
        viewModelScope.launch {
            delay(durationMs)
            if (pinManager.isPinSet()) {
                _authState.value = AuthState.Locked
            }
        }
    }
    
    fun resetError() {
        if (_authState.value is AuthState.Error) {
            if (pinManager.isLockedOut()) {
                // Remain locked
            } else if (!pinManager.isPinSet()) {
                _authState.value = AuthState.PinNotConfigured
            } else {
                _authState.value = AuthState.Locked
            }
        }
    }
}
