package com.livetracker.controller.core.update.domain.usecase

import com.livetracker.controller.core.update.domain.UpdateRepository
import com.livetracker.controller.core.update.model.UpdateState
import kotlinx.coroutines.flow.StateFlow

class ObserveUpdateStateUseCase(private val repository: UpdateRepository) {
    operator fun invoke(): StateFlow<UpdateState> {
        return repository.updateState
    }
}
