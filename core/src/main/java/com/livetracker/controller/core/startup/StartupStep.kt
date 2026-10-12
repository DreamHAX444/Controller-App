package com.livetracker.controller.core.startup

/**
 * Represents the status of an individual startup step.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}

/**
 * A single initialization step with a human-readable label and a suspend action.
 * [dependsOn] lists step labels that must complete before this step can run.
 */
data class StartupStep(
    val label: String,
    val dependsOn: List<String> = emptyList(),
    val action: suspend () -> Unit
)
