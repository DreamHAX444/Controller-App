package com.livetracker.controller.core.update.installer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface PackageInstallerEvent {
    object PendingUserActionLaunched : PackageInstallerEvent
    data class Success(val sessionId: Int) : PackageInstallerEvent
    data class Failure(val sessionId: Int, val statusCode: Int, val message: String) : PackageInstallerEvent
}

/**
 * Thread-safe global event bus connecting the system BroadcastReceiver callbacks to the update repository.
 */
object PackageInstallerStatusBus {
    private val _events = MutableSharedFlow<PackageInstallerEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PackageInstallerEvent> = _events.asSharedFlow()

    fun emitEvent(event: PackageInstallerEvent) {
        _events.tryEmit(event)
    }
}
