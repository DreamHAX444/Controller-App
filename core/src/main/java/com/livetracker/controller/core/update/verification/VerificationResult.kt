package com.livetracker.controller.core.update.verification

import com.livetracker.controller.core.update.model.UpdateFailureReason

/**
 * Result of artifact verification prior to installation.
 */
sealed interface VerificationResult {
    object Success : VerificationResult
    data class Failure(val reason: UpdateFailureReason, val details: String) : VerificationResult
}
