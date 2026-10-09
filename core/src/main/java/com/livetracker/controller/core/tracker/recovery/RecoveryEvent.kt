package com.livetracker.controller.core.tracker.recovery

import com.livetracker.controller.core.tracker.domain.TrackerId

sealed class RecoveryEvent {
    abstract val deviceId: TrackerId
    abstract val timestamp: Long
    abstract val attempt: Int
    
    data class RecoveryStarted(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int, val reason: String) : RecoveryEvent()
    data class RecoveryAttemptStarted(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int) : RecoveryEvent()
    data class RecoveryAttemptFailed(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int, val error: Throwable) : RecoveryEvent()
    data class RecoverySucceeded(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int) : RecoveryEvent()
    data class RecoveryExhausted(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int) : RecoveryEvent()
    data class RecoveryCancelled(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int, val reason: String) : RecoveryEvent()
    data class IntentionalDisconnect(override val deviceId: TrackerId, override val timestamp: Long, override val attempt: Int) : RecoveryEvent()
}
