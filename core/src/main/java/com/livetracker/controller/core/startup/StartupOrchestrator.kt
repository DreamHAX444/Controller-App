package com.livetracker.controller.core.startup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central startup coordinator. Runs initialization steps respecting dependencies,
 * parallelizing independent work, emitting observable progress, and handling failures.
 *
 * Designed for process-level lifecycle: create once in Application, run once.
 * Idempotent — calling [start] multiple times is safe (no-op after first run).
 *
 * @param steps The ordered list of startup steps. Steps without dependencies run concurrently.
 * @param scope The coroutine scope to launch initialization in (typically Application-level).
 * @param defaultDispatcher Dispatcher for background work (default: Dispatchers.Default).
 * @param stepTimeoutMs Maximum time any single step may take before being considered failed.
 */
class StartupOrchestrator(
    private val steps: List<StartupStep>,
    private val scope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stepTimeoutMs: Long = 10_000L
) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Idle)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private val stepStatuses = mutableMapOf<String, StepStatus>()
    private val mutex = Mutex()
    private var startupJob: Job? = null

    /**
     * Begin initialization. Safe to call multiple times — only the first call executes.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        steps.forEach { stepStatuses[it.label] = StepStatus.PENDING }

        startupJob = scope.launch {
            try {
                runSteps()
                _state.value = StartupState.Ready
            } catch (e: StartupFatalException) {
                _state.value = StartupState.FatalFailure(e.message ?: "Unknown fatal error")
            } catch (e: StartupRecoverableException) {
                _state.value = StartupState.RecoverableFailure(e.stepLabel, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Retry initialization after a recoverable failure.
     * Resets failed/pending steps and re-runs from where it left off.
     */
    fun retry() {
        if (_state.value !is StartupState.RecoverableFailure) return
        started.set(false)
        // Reset only failed/pending steps — keep succeeded steps
        stepStatuses.entries.forEach { (_, _) -> }
        for ((label, status) in stepStatuses) {
            if (status != StepStatus.SUCCEEDED) {
                stepStatuses[label] = StepStatus.PENDING
            }
        }
        start()
    }

    private suspend fun runSteps() {
        val totalSteps = steps.size
        var completedCount = stepStatuses.count { it.value == StepStatus.SUCCEEDED }

        while (completedCount < totalSteps) {
            // Find all steps that are pending and whose dependencies are satisfied
            val runnableSteps = mutex.withLock {
                steps.filter { step ->
                    stepStatuses[step.label] == StepStatus.PENDING &&
                        step.dependsOn.all { dep -> stepStatuses[dep] == StepStatus.SUCCEEDED }
                }
            }

            if (runnableSteps.isEmpty()) {
                // No runnable steps but not all completed — dependency cycle or all failed
                val pendingLabels = stepStatuses.filter { it.value == StepStatus.PENDING }.keys
                if (pendingLabels.isNotEmpty()) {
                    throw StartupFatalException(
                        "Startup stalled: steps ${pendingLabels.joinToString()} cannot proceed. " +
                            "Check for dependency cycles or prerequisite failures."
                    )
                }
                break
            }

            // Run all independent runnable steps concurrently
            val results = runnableSteps.map { step ->
                scope.async {
                    mutex.withLock {
                        stepStatuses[step.label] = StepStatus.RUNNING
                    }
                    _state.value = StartupState.Loading(
                        completedSteps = completedCount,
                        totalSteps = totalSteps,
                        currentStepLabel = step.label
                    )
                    runStep(step)
                }
            }.awaitAll()

            // Process results
            for ((index, step) in runnableSteps.withIndex()) {
                val result = results[index]
                mutex.withLock {
                    stepStatuses[step.label] = result
                }
                if (result == StepStatus.SUCCEEDED) {
                    completedCount++
                } else if (result == StepStatus.FAILED) {
                    // Check if any dependent step makes this fatal
                    val hasDependents = steps.any { it.dependsOn.contains(step.label) }
                    if (hasDependents) {
                        throw StartupRecoverableException(step.label, "Required step '${step.label}' failed")
                    }
                    // Non-blocking failure — skip dependents if any, count as completed
                    completedCount++
                }
            }

            // Update progress
            _state.value = StartupState.Loading(
                completedSteps = completedCount,
                totalSteps = totalSteps,
                currentStepLabel = if (completedCount < totalSteps) "Continuing..." else "Finalizing"
            )
        }
    }

    private suspend fun runStep(step: StartupStep): StepStatus {
        return try {
            withContext(defaultDispatcher) {
                withTimeout(stepTimeoutMs) {
                    step.action()
                }
            }
            StepStatus.SUCCEEDED
        } catch (e: Exception) {
            StepStatus.FAILED
        }
    }
}

private class StartupFatalException(message: String) : Exception(message)
private class StartupRecoverableException(val stepLabel: String, message: String) : Exception(message)
