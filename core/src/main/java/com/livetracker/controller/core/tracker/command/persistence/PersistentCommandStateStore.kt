package com.livetracker.controller.core.tracker.command.persistence

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Production-ready, durable [CommandStateStore] adhering strictly to Ponytail principles:
 * - Uses native Android [SharedPreferences] and existing [kotlinx.serialization].
 * - Zero external libraries added.
 * - Thread/coroutine-safe via [Mutex].
 * - Strictly protects terminal states from being overwritten by duplicate/replayed submissions.
 * - Enforces monotonic non-terminal status transitions.
 */
class PersistentCommandStateStore(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : CommandStateStore {

    constructor(context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ioDispatcher = Dispatchers.IO
    )

    private val mutex = Mutex()
    private val memoryCache = mutableMapOf<String, TrackedCommand>()
    private val _flow = MutableStateFlow<List<TrackedCommand>>(emptyList())
    private var isInitialized = false

    private suspend fun ensureLoadedLocked() {
        if (!isInitialized) {
            val loaded = withContext(ioDispatcher) { loadFromDisk() }
            memoryCache.clear()
            memoryCache.putAll(loaded)
            _flow.value = memoryCache.values.toList()
            isInitialized = true
        }
    }

    override suspend fun recordCommand(command: TrackedCommand): Result<Unit> = mutex.withLock {
        ensureLoadedLocked()
        val existing = memoryCache[command.idempotencyKey]

        // GUARD 1: Terminal states are strictly immutable — duplicate submissions cannot resurrect them
        if (existing != null && existing.status.isTerminal()) {
            return Result.success(Unit)
        }

        val sanitizedParams = if (command.paramsJson != null && command.paramsJson!!.length > 512) {
            command.paramsJson!!.take(512) + "... [truncated]"
        } else {
            command.paramsJson
        }

        // GUARD 2: Preserve existing sequenceId, createdAtEpochMs, and increment retryCount if already registered
        val merged = if (existing != null) {
            command.copy(
                commandId = command.commandId ?: existing.commandId,
                paramsJson = sanitizedParams ?: existing.paramsJson,
                createdAtEpochMs = existing.createdAtEpochMs,
                retryCount = existing.retryCount + 1,
                status = if (existing.status.canTransitionTo(command.status)) {
                    command.status
                } else {
                    existing.status
                }
            )
        } else {
            command.copy(paramsJson = sanitizedParams)
        }

        memoryCache[merged.idempotencyKey] = merged
        pruneLocked()
        withContext(ioDispatcher) { persistToDiskLocked() }
        _flow.value = memoryCache.values.toList()
        Result.success(Unit)
    }

    override suspend fun updateStatus(
        idempotencyKey: String,
        newStatus: CommandStatus,
        resultMessage: String?
    ): Result<Unit> = mutex.withLock {
        ensureLoadedLocked()
        val existing = memoryCache[idempotencyKey]
            ?: return Result.failure(Exception("Command not found: $idempotencyKey"))

        // GUARD 1: Terminal states are strictly immutable
        if (existing.status.isTerminal()) {
            return Result.success(Unit)
        }

        // GUARD 2: Monotonic transition enforcement — prevent regressions
        if (!existing.status.canTransitionTo(newStatus)) {
            return Result.success(Unit)
        }

        val sanitizedMessage = when {
            resultMessage == null -> existing.resultMessage
            resultMessage.length > 512 -> resultMessage.take(512) + "... [truncated]"
            else -> resultMessage
        }

        val updated = existing.copy(
            status = newStatus,
            resultMessage = sanitizedMessage
        )
        memoryCache[idempotencyKey] = updated
        pruneLocked()
        withContext(ioDispatcher) { persistToDiskLocked() }
        _flow.value = memoryCache.values.toList()
        Result.success(Unit)
    }

    private fun pruneLocked() {
        if (memoryCache.size > 50) {
            val terminalKeys = memoryCache.entries
                .filter { it.value.status.isTerminal() }
                .sortedBy { it.value.createdAtEpochMs }
                .map { it.key }
            val toRemove = (memoryCache.size - 50).coerceAtMost(terminalKeys.size)
            for (i in 0 until toRemove) {
                memoryCache.remove(terminalKeys[i])
            }
        }
    }

    override suspend fun getCommand(idempotencyKey: String): TrackedCommand? = mutex.withLock {
        ensureLoadedLocked()
        memoryCache[idempotencyKey]
    }

    override suspend fun getCommandBySequenceId(sequenceId: Long): TrackedCommand? = mutex.withLock {
        ensureLoadedLocked()
        memoryCache.values.firstOrNull { it.commandId == sequenceId }
    }

    override fun observePendingCommands(): Flow<List<TrackedCommand>> {
        return _flow
            .onStart { mutex.withLock { ensureLoadedLocked() } }
            .map { list -> list.filter { !it.status.isTerminal() } }
    }

    override suspend fun clearOldCommands(olderThanMs: Long): Int = mutex.withLock {
        ensureLoadedLocked()
        val now = timeProvider()
        var removed = 0
        val iterator = memoryCache.entries.iterator()
        while (iterator.hasNext()) {
            val (_, cmd) = iterator.next()
            if (cmd.status.isTerminal() && (now - cmd.createdAtEpochMs) > olderThanMs) {
                iterator.remove()
                removed++
            }
        }
        if (removed > 0) {
            withContext(ioDispatcher) { persistToDiskLocked() }
            _flow.value = memoryCache.values.toList()
        }
        removed
    }

    private fun loadFromDisk(): Map<String, TrackedCommand> {
        val json = prefs.getString(KEY_COMMANDS, null) ?: return emptyMap()
        return try {
            val decoded = jsonFormat.decodeFromString<Map<String, TrackedCommand>>(json)
            // Auto-sanitize existing bloated entries if loading from legacy inflated prefs
            val sanitized = decoded.entries
                .sortedByDescending { it.value.createdAtEpochMs }
                .take(50)
                .associate { (k, v) ->
                    k to v.copy(
                        resultMessage = v.resultMessage?.let { if (it.length > 512) it.take(512) + "... [truncated]" else it },
                        paramsJson = v.paramsJson?.let { if (it.length > 512) it.take(512) + "... [truncated]" else it }
                    )
                }
            sanitized
        } catch (e: Exception) {
            // Backup corrupted payload and fail-safe with empty map
            prefs.edit().putString(KEY_COMMANDS_BACKUP, json).remove(KEY_COMMANDS).apply()
            emptyMap()
        }
    }

    private fun persistToDiskLocked() {
        val json = jsonFormat.encodeToString(memoryCache)
        prefs.edit().putString(KEY_COMMANDS, json).apply()
    }

    companion object {
        const val PREFS_NAME = "trace_command_store_prefs"
        const val KEY_COMMANDS = "tracked_commands_json"
        const val KEY_COMMANDS_BACKUP = "tracked_commands_corrupted_backup"

        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
