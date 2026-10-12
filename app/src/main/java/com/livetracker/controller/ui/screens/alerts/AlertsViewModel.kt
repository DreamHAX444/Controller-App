package com.livetracker.controller.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(
    private val alertRepository: AlertRepository
) : ViewModel() {
    private val refreshTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 1, 
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    ).apply { tryEmit(Unit) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val alerts: StateFlow<List<Alert>> = refreshTrigger.transformLatest {
        while (true) {
            emit(alertRepository.getUnacknowledgedAlerts())
            kotlinx.coroutines.delay(2000L)
        }
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )


    fun acknowledgeAlert(alertId: String) {
        viewModelScope.launch {
            alertRepository.acknowledgeAlert(alertId)
            refreshTrigger.tryEmit(Unit)
        }
    }
}
