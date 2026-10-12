package com.livetracker.controller.core.update.domain.usecase

import com.livetracker.controller.core.update.domain.UpdateRepository

class DownloadUpdateUseCase(private val repository: UpdateRepository) {
    suspend operator fun invoke(): Result<Unit> {
        return repository.startDownload()
    }

    fun cancel() {
        repository.cancelDownload()
    }
}
