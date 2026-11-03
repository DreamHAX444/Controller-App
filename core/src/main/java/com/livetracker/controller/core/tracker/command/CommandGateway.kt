package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand

/**
 * Authoritative gateway for submitting commands to the TRACE platform.
 *
 * Enforces the Phase 0 Security Gate: all protected commands must be submitted
 * via the authenticated Edge Function gateway, cryptographically signed, and
 * recorded in the server audit log.
 */
interface CommandGateway {

    /**
     * Submits a protected command to the server-side command gateway.
     *
     * @param command The tracker command to submit.
     * @param session The caller's authenticated session containing GoTrue credentials.
     * @param idempotencyKey Unique UUID idempotency key to prevent double execution.
     * @return [CommandResult] reflecting the gateway outcome.
     *
     * Invariant: Never falls back to insecure direct transport broadcast.
     */
    suspend fun submitCommand(
        command: TrackerCommand,
        session: Session,
        idempotencyKey: String = java.util.UUID.randomUUID().toString()
    ): CommandResult
}

class CommandSubmissionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
