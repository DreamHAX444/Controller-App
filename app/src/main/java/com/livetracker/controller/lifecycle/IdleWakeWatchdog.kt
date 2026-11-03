package com.livetracker.controller.lifecycle

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Monitors process lifecycle. When the controller app is minimized to the background,
 * starts an inactivity countdown (default 5 minutes). If the app remains in the background
 * when the timer expires, automatically sends SLEEP commands to all active/connected trackers
 * to conserve battery and avoid wasted telemetry.
 */
class IdleWakeWatchdog(
    private val trackerDeviceRepository: TrackerDeviceRepository,
    private val sendCommand: suspend (TrackerCommand) -> Unit,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = 5 * 60 * 1000L, // 5 minutes default
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : DefaultLifecycleObserver {

    private var countdownJob: Job? = null

    suspend fun start() {
        withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@IdleWakeWatchdog)
        }
        Log.d(TAG, "IdleWakeWatchdog started with ${idleTimeoutMs / 1000}s timeout")
    }

    suspend fun stop() {
        withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this@IdleWakeWatchdog)
        }
        cancelCountdown()
    }

    override fun onStart(owner: LifecycleOwner) {
        // App brought back to foreground — cancel any running idle auto-sleep countdown
        cancelCountdown()
        Log.d(TAG, "Controller resumed to foreground: Idle watchdog reset")
    }

    override fun onStop(owner: LifecycleOwner) {
        // App moved to background — begin idle countdown
        cancelCountdown()
        Log.d(TAG, "Controller minimized: Starting ${idleTimeoutMs / 1000}s idle watchdog countdown")
        countdownJob = scope.launch(dispatcher) {
            delay(idleTimeoutMs)
            executeAutoSleep()
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private suspend fun executeAutoSleep() {
        try {
            val devices = trackerDeviceRepository.getAllDevices()
            val activeDevices = devices.filter { it.connectionState == ConnectionState.CONNECTED }
            if (activeDevices.isEmpty()) {
                Log.d(TAG, "Idle watchdog elapsed: No active connected trackers found.")
                return
            }

            Log.i(TAG, "Idle watchdog elapsed after ${idleTimeoutMs / 1000}s in background: Dispatching SLEEP to ${activeDevices.size} connected tracker(s)")
            for (device in activeDevices) {
                try {
                    val cmd = TrackerCommand.Sleep(deviceId = device.deviceId)
                    sendCommand(cmd)
                    Log.i(TAG, "Dispatched SLEEP command to tracker ${device.deviceId.value}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dispatch SLEEP to tracker ${device.deviceId.value}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing auto-sleep in watchdog", e)
        }
    }

    companion object {
        private const val TAG = "IdleWakeWatchdog"
    }
}
