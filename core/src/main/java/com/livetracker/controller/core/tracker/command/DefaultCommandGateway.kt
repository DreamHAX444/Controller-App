package com.livetracker.controller.core.tracker.command

import android.util.Log
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-driven command gateway implementing Phase 2 Zero PostgREST Select Polling.
 *
 * Employs [CompletableDeferred] awaiting WebSocket `command_ack` broadcast envelopes
 * instead of HTTP PostgREST polling loops.
 */
open class DefaultCommandGateway(
    private val delegate: CommandGateway? = null,
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.client },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val defaultTimeoutMs: Long = 15_000L,
    commandsChannel: RealtimeChannel? = null
) : CommandGateway {

    private val tag = "DefaultCommandGateway"
    val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<CommandAckEnvelope>>()

    private var activeCommandsChannel: RealtimeChannel? = commandsChannel
    private var ackSubscriptionJob: Job? = null

    init {
        if (commandsChannel != null) {
            bindCommandsChannel(commandsChannel)
        } else {
            ensureChannelSubscription()
        }
    }

    fun bindCommandsChannel(channel: RealtimeChannel) {
        activeCommandsChannel = channel
        ackSubscriptionJob?.cancel()
        ackSubscriptionJob = scope.launch {
            try {
                if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED && channel.status.value != RealtimeChannel.Status.SUBSCRIBING) {
                    try { channel.subscribe(blockUntilSubscribed = false) } catch (_: Exception) {}
                }
                channel.broadcastFlow<CommandAckEnvelope>("command_ack").collect { ack ->
                    handleIncomingAck(ack)
                }
            } catch (e: Exception) {
                Log.w(tag, "command_ack broadcast flow error: ${e.message}")
            }
        }
    }

    private fun ensureChannelSubscription() {
        if (activeCommandsChannel != null && ackSubscriptionJob?.isActive == true) return
        try {
            val ch = clientProvider().channel("public:commands")
            bindCommandsChannel(ch)
        } catch (_: Throwable) {
            // Channel may not be available in test/offline environments
        }
    }

    internal fun handleIncomingAck(ack: CommandAckEnvelope) {
        val deferred = pendingAcks.remove(ack.idempotencyKey)
            ?: ack.commandId?.let { pendingAcks.remove(it) }
            ?: ack.sequenceId?.let { pendingAcks.remove(it.toString()) }
        deferred?.complete(ack)
    }

    suspend fun awaitAck(
        idempotencyKey: String,
        timeoutMs: Long = defaultTimeoutMs
    ): CommandAckEnvelope? {
        val deferred = CompletableDeferred<CommandAckEnvelope>()
        pendingAcks[idempotencyKey] = deferred
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingAcks.remove(idempotencyKey)
        }
    }

    override suspend fun submitCommand(
        command: TrackerCommand,
        session: Session,
        idempotencyKey: String
    ): CommandResult {
        if (delegate == null) {
            return CommandResult.Failed(
                commandId = command.commandId,
                deviceId = command.deviceId,
                reason = "No backing CommandGateway delegate configured"
            )
        }

        ensureChannelSubscription()

        // Register deferred before dispatching to eliminate fast WebSocket ACK races
        val ackDeferred = CompletableDeferred<CommandAckEnvelope>()
        pendingAcks[idempotencyKey] = ackDeferred

        val submissionResult = try {
            delegate.submitCommand(command, session, idempotencyKey)
        } catch (t: Throwable) {
            pendingAcks.remove(idempotencyKey)
            throw t
        }

        if (submissionResult !is CommandResult.Accepted) {
            pendingAcks.remove(idempotencyKey)
            return submissionResult
        }

        // Await event-driven WebSocket command_ack
        val ack = try {
            withTimeoutOrNull(defaultTimeoutMs) {
                ackDeferred.await()
            }
        } finally {
            pendingAcks.remove(idempotencyKey)
        }

        if (ack != null) {
            val isSuccess = ack.status.equals("COMPLETED", ignoreCase = true) ||
                ack.status.equals("EXECUTED", ignoreCase = true)
            return if (isSuccess) {
                CommandResult.Completed(
                    commandId = ack.commandId ?: command.commandId,
                    deviceId = command.deviceId,
                    payload = ack.resultPayload
                )
            } else {
                CommandResult.Failed(
                    commandId = ack.commandId ?: command.commandId,
                    deviceId = command.deviceId,
                    reason = ack.errorMessage ?: ack.resultPayload ?: "Command execution failed"
                )
            }
        }

        // Graceful fallback: return initial Accepted result if Realtime ACK timed out
        Log.w(tag, "command_ack timed out after ${defaultTimeoutMs}ms for $idempotencyKey, returning Accepted")
        return submissionResult
    }
}
