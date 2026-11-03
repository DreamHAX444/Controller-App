package com.livetracker.controller.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.livetracker.controller.core.security.TimeProvider

/**
 * Android-specific implementation of [TimeProvider].
 *
 * - Uses [SystemClock.elapsedRealtime] for monotonic time immune to user wall-clock tampering.
 * - Uses [Settings.Global.BOOT_COUNT] to detect device reboots across lockout sessions.
 */
class AndroidTimeProvider(private val context: Context) : TimeProvider {

    override fun currentTimeMs(): Long {
        return System.currentTimeMillis()
    }

    override fun elapsedRealtimeMs(): Long {
        return SystemClock.elapsedRealtime()
    }

    override fun bootCount(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        } catch (_: Exception) {
            0
        }
    }
}
