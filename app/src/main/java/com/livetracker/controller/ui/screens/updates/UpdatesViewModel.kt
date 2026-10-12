package com.livetracker.controller.ui.screens.updates

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.update.domain.usecase.CheckForUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.DismissUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.DownloadUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.InstallUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.ObserveUpdateStateUseCase
import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.installer.UpdateInstaller
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.core.update.storage.UpdatePreferencesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UpdatesUiState(
    val currentVersionCode: Long,
    val currentVersionName: String,
    val updateState: UpdateState = UpdateState.Idle,
    val lastCheckedEpochMs: Long = 0L,
    val channel: String = "stable",
    val canRequestPackageInstalls: Boolean = true
)

sealed interface UpdatesUiEffect {
    data class LaunchSettings(val intent: Intent) : UpdatesUiEffect
}

class UpdatesViewModel(
    val currentVersionCode: Long,
    val currentVersionName: String,
    private val checkForUpdateUseCase: CheckForUpdateUseCase,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val installUpdateUseCase: InstallUpdateUseCase,
    private val dismissUpdateUseCase: DismissUpdateUseCase,
    private val observeUpdateStateUseCase: ObserveUpdateStateUseCase,
    private val preferencesRepository: UpdatePreferencesRepository,
    private val updateInstaller: UpdateInstaller
) : ViewModel() {

    private val _canInstallState = MutableStateFlow(updateInstaller.canRequestPackageInstalls())
    private val _effects = MutableSharedFlow<UpdatesUiEffect>()
    val effects: SharedFlow<UpdatesUiEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<UpdatesUiState> = combine(
        observeUpdateStateUseCase(),
        preferencesRepository.lastCheckedEpochMs,
        preferencesRepository.selectedChannel,
        _canInstallState
    ) { state, lastChecked, channel, canInstall ->
        UpdatesUiState(
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            updateState = state,
            lastCheckedEpochMs = lastChecked,
            channel = channel,
            canRequestPackageInstalls = canInstall
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UpdatesUiState(
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            canRequestPackageInstalls = updateInstaller.canRequestPackageInstalls()
        )
    )

    fun refreshPermissionStatus() {
        _canInstallState.value = updateInstaller.canRequestPackageInstalls()
    }

    fun checkForUpdates(force: Boolean = true) {
        viewModelScope.launch {
            checkForUpdateUseCase(force = force)
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            downloadUpdateUseCase()
        }
    }

    fun cancelDownload() {
        downloadUpdateUseCase.cancel()
    }

    fun installUpdate() {
        viewModelScope.launch {
            refreshPermissionStatus()
            if (!updateInstaller.canRequestPackageInstalls()) {
                val settingsIntent = updateInstaller.getManageUnknownAppSourcesIntent()
                if (settingsIntent != null) {
                    _effects.emit(UpdatesUiEffect.LaunchSettings(settingsIntent))
                    return@launch
                }
            }
            val result = installUpdateUseCase()
            if (result.isSuccess) {
                val reqResult = result.getOrNull()
                if (reqResult is InstallationRequestResult.RequiresSettingsPermission) {
                    reqResult.settingsIntent?.let {
                        _effects.emit(UpdatesUiEffect.LaunchSettings(it))
                    }
                }
            }
        }
    }

    fun dismissUpdate(versionCode: Long) {
        viewModelScope.launch {
            dismissUpdateUseCase(versionCode)
        }
    }

    fun openSettingsForPermission() {
        viewModelScope.launch {
            val intent = updateInstaller.getManageUnknownAppSourcesIntent()
            if (intent != null) {
                _effects.emit(UpdatesUiEffect.LaunchSettings(intent))
            }
        }
    }
}
