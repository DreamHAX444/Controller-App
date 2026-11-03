package com.livetracker.controller.core.security

/**
 * Abstraction for wall-clock time, monotonic elapsed time, and device boot tracking.
 * Used by security mechanisms to defend against time-tampering and reboot attacks.
 */
interface TimeProvider {
    /**
     * Current wall-clock time in milliseconds since Unix epoch.
     */
    fun currentTimeMs(): Long

    /**
     * Monotonic time in milliseconds since system boot, including deep sleep.
     * Guaranteed never to roll backward or forward due to system clock changes.
     */
    fun elapsedRealtimeMs(): Long

    /**
     * Boot count indicator of the device (increments on each device reboot).
     */
    fun bootCount(): Int
}

/**
 * Default fallback implementation for JVM/standalone environments.
 */
object DefaultTimeProvider : TimeProvider {
    override fun currentTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
    override fun bootCount(): Int = 1
}
