package com.livetracker.controller.core.tracker.command.persistence

import kotlinx.serialization.Serializable

@Serializable
enum class CommandStatus {
    PENDING,
    SUBMITTED,
    ACKNOWLEDGED,
    EXECUTED,
    FAILED,
    EXPIRED,
    TIMEOUT;

    fun isTerminal(): Boolean = this in setOf(EXECUTED, FAILED, EXPIRED, TIMEOUT)

    /**
     * Enforces strict monotonic lifecycle state progression.
     * Terminal states are immutable.
     * Non-terminal states cannot regress (e.g. ACKNOWLEDGED cannot regress to SUBMITTED or PENDING).
     */
    fun canTransitionTo(next: CommandStatus): Boolean {
        if (this.isTerminal()) return false
        if (this == next) return true
        return when (this) {
            PENDING -> next in setOf(SUBMITTED, ACKNOWLEDGED, EXECUTED, FAILED, EXPIRED, TIMEOUT)
            SUBMITTED -> next in setOf(ACKNOWLEDGED, EXECUTED, FAILED, EXPIRED, TIMEOUT)
            ACKNOWLEDGED -> next in setOf(EXECUTED, FAILED, EXPIRED, TIMEOUT)
            else -> false
        }
    }
}
