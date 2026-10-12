package com.livetracker.controller.core.startup

/**
 * Represents the overall application startup state.
 * Observed by the loading screen to display progress and route to the correct destination.
 */
sealed class StartupState {
    /** Startup has not begun yet. */
    data object Idle : StartupState()

    /** Startup is in progress. [completedSteps] and [totalSteps] drive the progress indicator.
     *  [currentStepLabel] describes what is currently running. */
    data class Loading(
        val completedSteps: Int,
        val totalSteps: Int,
        val currentStepLabel: String
    ) : StartupState() {
        val progress: Float get() = if (totalSteps > 0) completedSteps.toFloat() / totalSteps else 0f
    }

    /** All required startup tasks completed successfully. The app is ready. */
    data object Ready : StartupState()

    /** A recoverable failure occurred. The user may retry. */
    data class RecoverableFailure(
        val failedStep: String,
        val message: String
    ) : StartupState()

    /** A fatal failure occurred. The app cannot proceed. */
    data class FatalFailure(
        val message: String
    ) : StartupState()
}
