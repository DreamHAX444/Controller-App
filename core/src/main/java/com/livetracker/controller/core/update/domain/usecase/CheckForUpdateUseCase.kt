package com.livetracker.controller.core.update.domain.usecase

import com.livetracker.controller.core.update.domain.UpdateRepository
import com.livetracker.controller.core.update.model.UpdateInfo

class CheckForUpdateUseCase(private val repository: UpdateRepository) {
    suspend operator fun invoke(force: Boolean = false): Result<UpdateInfo> {
        return repository.checkForUpdates(force)
    }
}
