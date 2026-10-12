package com.livetracker.controller.core.update.domain.usecase

import com.livetracker.controller.core.update.domain.UpdateRepository

class DismissUpdateUseCase(private val repository: UpdateRepository) {
    suspend operator fun invoke(versionCode: Long) {
        repository.dismissUpdate(versionCode)
    }
}
