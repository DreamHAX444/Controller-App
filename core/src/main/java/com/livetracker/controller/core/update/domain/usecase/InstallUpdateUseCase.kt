package com.livetracker.controller.core.update.domain.usecase

import com.livetracker.controller.core.update.domain.UpdateRepository
import com.livetracker.controller.core.update.installer.InstallationRequestResult

class InstallUpdateUseCase(private val repository: UpdateRepository) {
    suspend operator fun invoke(): Result<InstallationRequestResult> {
        return repository.installDownloadedUpdate()
    }
}
