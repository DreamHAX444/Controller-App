package com.livetracker.controller.core.tracker.command.persistence

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

import kotlinx.coroutines.flow.map

interface CommandStateStore {
    suspend fun recordCommand(command: TrackedCommand): Result<Unit>
    suspend fun updateStatus(idempotencyKey: String, newStatus: CommandStatus, resultMessage: String? = null): Result<Unit>
    suspend fun getCommand(idempotencyKey: String): TrackedCommand?
    suspend fun getCommandBySequenceId(sequenceId: Long): TrackedCommand?
    fun observePendingCommands(): Flow<List<TrackedCommand>>
    suspend fun clearOldCommands(olderThanMs: Long): Int
}

class InMemoryCommandStateStore : CommandStateStore {
    private val commands = ConcurrentHashMap<String, TrackedCommand>()
    private val _flow = MutableStateFlow<List<TrackedCommand>>(emptyList())

    override suspend fun recordCommand(command: TrackedCommand): Result<Unit> {
        val existing = commands[command.idempotencyKey]
        if (existing != null && existing.status.isTerminal()) {
            return Result.success(Unit)
        }
        val merged = if (existing != null) {
            command.copy(
                commandId = command.commandId ?: existing.commandId,
                createdAtEpochMs = existing.createdAtEpochMs,
                retryCount = existing.retryCount + 1,
                status = if (existing.status.canTransitionTo(command.status)) {
                    command.status
                } else {
                    existing.status
                }
            )
        } else {
            command
        }
        commands[merged.idempotencyKey] = merged
        _flow.value = commands.values.toList()
        return Result.success(Unit)
    }

    override suspend fun updateStatus(
        idempotencyKey: String,
        newStatus: CommandStatus,
        resultMessage: String?
    ): Result<Unit> {
        val existing = commands[idempotencyKey] ?: return Result.failure(Exception("Command not found: $idempotencyKey"))
        // Terminal states are strictly immutable
        if (existing.status.isTerminal()) {
            return Result.success(Unit)
        }
        // Monotonic transition enforcement: do not regress state
        if (!existing.status.canTransitionTo(newStatus)) {
            return Result.success(Unit)
        }
        val updated = existing.copy(
            status = newStatus,
            resultMessage = resultMessage ?: existing.resultMessage
        )
        commands[idempotencyKey] = updated
        _flow.value = commands.values.toList()
        return Result.success(Unit)
    }

    override suspend fun getCommand(idempotencyKey: String): TrackedCommand? = commands[idempotencyKey]

    override suspend fun getCommandBySequenceId(sequenceId: Long): TrackedCommand? {
        return commands.values.firstOrNull { it.commandId == sequenceId }
    }

    override fun observePendingCommands(): Flow<List<TrackedCommand>> {
        return _flow.map { list -> list.filter { !it.status.isTerminal() } }
    }

    override suspend fun clearOldCommands(olderThanMs: Long): Int {
        val now = System.currentTimeMillis()
        var removed = 0
        commands.entries.removeIf { (_, cmd) ->
            if (cmd.status.isTerminal() && (now - cmd.createdAtEpochMs) > olderThanMs) {
                removed++
                true
            } else {
                false
            }
        }
        if (removed > 0) {
            _flow.value = commands.values.toList()
        }
        return removed
    }
}
