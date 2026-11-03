package com.livetracker.controller.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.files.domain.FileTransferRepository
import com.livetracker.controller.core.tracker.files.domain.FileTransferState
import com.livetracker.controller.core.tracker.files.domain.RemoteFileItem
import com.livetracker.controller.core.tracker.files.domain.TransferProgress
import com.livetracker.controller.core.tracker.files.domain.TransferStatus
import com.livetracker.controller.ui.util.DeviceNameFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

enum class FileCategory(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    AUDIO("Audio"),
    VIDEOS("Videos"),
    DOCUMENTS("Documents")
}

enum class DownloadLocationType(val label: String, val description: String) {
    PUBLIC_DOWNLOADS("Public Downloads", "/storage/emulated/0/Download"),
    APP_STORAGE("App Sandbox", "Android/data/com.livetracker.controller/files"),
    ALWAYS_ASK("Always Ask", "Choose destination on each download")
}

data class DownloadedFileRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

data class BatchDownloadItemProgress(
    val file: RemoteFileItem,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = file.sizeBytes,
    val status: TransferStatus = TransferStatus.QUEUED,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class BatchDownloadState(
    val isActive: Boolean = false,
    val items: List<BatchDownloadItemProgress> = emptyList(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val isCompleted: Boolean = false,
    val hasErrors: Boolean = false
) {
    val overallProgressFraction: Float
        get() = if (totalCount > 0) (completedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
}

sealed interface DownloadLocationPrompt {
    data class Single(val item: RemoteFileItem) : DownloadLocationPrompt
    data class Batch(val items: List<RemoteFileItem>) : DownloadLocationPrompt
}

sealed interface FileConflictPrompt {
    data class Single(
        val item: RemoteFileItem,
        val destinationDir: File,
        val existingSizeBytes: Long
    ) : FileConflictPrompt

    data class Batch(
        val conflictingFiles: List<RemoteFileItem>,
        val allSelectedFiles: List<RemoteFileItem>,
        val destinationDir: File
    ) : FileConflictPrompt
}

data class FilesTrackerOption(
    val deviceId: TrackerId,
    val displayName: String,
    val isOnline: Boolean,
    val isFilesSupported: Boolean
)

data class FilesUiState(
    val deviceId: String = "",
    val displayName: String = "",
    val availableTrackers: List<FilesTrackerOption> = emptyList(),
    val currentPath: String = "",
    val files: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val activeTransfers: List<TransferProgress> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val selectedCategory: FileCategory = FileCategory.ALL,
    val isGridView: Boolean = false,
    val searchQuery: String = "",
    val downloadLocation: DownloadLocationType = DownloadLocationType.PUBLIC_DOWNLOADS,
    val downloadedFiles: List<DownloadedFileRecord> = emptyList(),
    val downloadLocationPrompt: DownloadLocationPrompt? = null,
    val showTransfersSheet: Boolean = false,
    val isSelectionMode: Boolean = false,
    val selectedFilePaths: Set<String> = emptySet(),
    val batchDownloadState: BatchDownloadState = BatchDownloadState(),
    val showBatchProgressDialog: Boolean = false,
    val fileConflictPrompt: FileConflictPrompt? = null
) {
    val fileToDownloadPrompt: RemoteFileItem?
        get() = (downloadLocationPrompt as? DownloadLocationPrompt.Single)?.item

    val ongoingTransfers: List<TransferProgress>
        get() = activeTransfers.filter { 
            it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.QUEUED 
        }

    val completedTransfers: List<TransferProgress>
        get() = activeTransfers.filter { 
            it.status == TransferStatus.COMPLETED 
        }

    val canGoUp: Boolean
        get() = currentPath.isNotBlank() && 
                currentPath != "/" && 
                !currentPath.trimEnd('/').equals("/storage/emulated/0", ignoreCase = true)

    val displayFiles: List<RemoteFileItem>
        get() {
            var result = files
            if (searchQuery.isNotBlank()) {
                result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            if (selectedCategory != FileCategory.ALL) {
                result = result.filter { item ->
                    if (item.isDirectory) true
                    else when (selectedCategory) {
                        FileCategory.IMAGES -> isImageFile(item.name, item.mimeType)
                        FileCategory.AUDIO -> isAudioFile(item.name, item.mimeType)
                        FileCategory.VIDEOS -> isVideoFile(item.name, item.mimeType)
                        FileCategory.DOCUMENTS -> isDocumentFile(item.name, item.mimeType)
                        FileCategory.ALL -> true
                    }
                }
            }
            return result
        }

    private fun isImageFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("image/") == true) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    }

    private fun isAudioFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("audio/") == true) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp3", "m4a", "wav", "aac", "flac", "ogg", "opus")
    }

    private fun isVideoFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("video/") == true) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp")
    }

    private fun isDocumentFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("text/") == true || mime == "application/pdf") return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "xml", "json")
    }
}

class FilesViewModel(
    private val fileTransferRepository: FileTransferRepository,
    private val deviceSelectionManager: DeviceSelectionManager,
    private val sessionRepository: SessionRepository,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var filesObserveJob: Job? = null

    init {
        viewModelScope.launch {
            deviceSelectionManager.getSelection().collectLatest { selection ->
                val primaryId = when (selection) {
                    is DeviceSelection.Single -> selection.deviceId
                    is DeviceSelection.Multiple -> selection.deviceIds.firstOrNull()
                    else -> null
                }
                if (primaryId != null) {
                    val initialName = trackerDeviceRepository?.getDevice(primaryId)?.displayName ?: primaryId.value
                    _uiState.update { it.copy(deviceId = primaryId.value, displayName = initialName) }
                    observeDeviceFiles(primaryId)
                    refreshFiles("")
                }
            }
        }
        if (trackerDeviceRepository != null) {
            viewModelScope.launch {
                trackerDeviceRepository.observeAllDevices().collect { devices ->
                    val options = devices.map { dev ->
                        FilesTrackerOption(
                            deviceId = dev.deviceId,
                            displayName = DeviceNameFormatter.formatFriendlyName(dev.displayName, dev.deviceId.value),
                            isOnline = dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED ||
                                dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTING,
                            isFilesSupported = dev.capabilities.fileTransfer
                        )
                    }
                    val currentId = _uiState.value.deviceId
                    val currentDevice = devices.find { it.deviceId.value == currentId }
                    val hasConnectedDevice = devices.any { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED }
                    val shouldAutoSwitch = currentId.isEmpty() || (currentDevice?.connectionState != com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED && hasConnectedDevice)
                    if (devices.isNotEmpty() && shouldAutoSwitch) {
                        val preferred = devices.firstOrNull { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED } ?: devices.first()
                        _uiState.update {
                            it.copy(
                                deviceId = preferred.deviceId.value,
                                displayName = preferred.displayName,
                                availableTrackers = options
                            )
                        }
                        observeDeviceFiles(preferred.deviceId)
                        refreshFiles("")
                    } else if (currentId.isNotEmpty()) {
                        val matched = devices.find { it.deviceId.value == currentId }
                        _uiState.update {
                            it.copy(
                                displayName = matched?.displayName ?: it.displayName,
                                availableTrackers = options
                            )
                        }
                    } else {
                        _uiState.update { it.copy(availableTrackers = options) }
                    }
                }
            }
        }
    }

    fun selectTracker(newDeviceId: TrackerId) {
        val currentId = _uiState.value.deviceId
        if (currentId == newDeviceId.value) return

        viewModelScope.launch {
            val devName = trackerDeviceRepository?.getDevice(newDeviceId)?.displayName ?: newDeviceId.value
            _uiState.update {
                it.copy(
                    deviceId = newDeviceId.value,
                    displayName = devName,
                    currentPath = "",
                    files = emptyList(),
                    activeTransfers = emptyList(),
                    errorMessage = null,
                    successMessage = null
                )
            }
            observeDeviceFiles(newDeviceId)
            refreshFiles("")
        }
    }

    private fun observeDeviceFiles(deviceId: TrackerId) {
        filesObserveJob?.cancel()
        filesObserveJob = viewModelScope.launch {
            fileTransferRepository.observeFileTransferState(deviceId).collect { state ->
                _uiState.update {
                    it.copy(
                        currentPath = state.currentPath,
                        files = state.files,
                        isLoading = state.isLoading,
                        activeTransfers = state.activeTransfers,
                        errorMessage = state.errorMessage
                    )
                }
            }
        }
    }

    fun refreshFiles(path: String = _uiState.value.currentPath) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return

        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                fileTransferRepository.listFiles(TrackerId(devId), path, session)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to list files") }
            }
        }
    }

    fun openDirectory(directoryPath: String) {
        refreshFiles(directoryPath)
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath.trimEnd('/')
        if (current.equals("/storage/emulated/0", ignoreCase = true) || current.isEmpty() || current == "/") {
            return
        }
        val parent = current.substringBeforeLast('/', "")
        if (parent == "/storage/emulated" || parent == "/storage" || parent.isEmpty()) {
            refreshFiles("/storage/emulated/0")
        } else {
            refreshFiles(parent)
        }
    }

    fun getEffectiveDownloadDestination(): File {
        return when (_uiState.value.downloadLocation) {
            DownloadLocationType.PUBLIC_DOWNLOADS, DownloadLocationType.ALWAYS_ASK -> {
                val pub = try {
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                } catch (_: Exception) {
                    null
                }
                if (pub != null && (pub.exists() || pub.mkdirs())) {
                    pub
                } else {
                    val defaultDir = File("/storage/emulated/0/Download")
                    if (defaultDir.exists() || defaultDir.mkdirs()) {
                        defaultDir
                    } else {
                        File(System.getProperty("java.io.tmpdir", "."), "Download").apply { mkdirs() }
                    }
                }
            }
            DownloadLocationType.APP_STORAGE -> {
                val appDir = File("/storage/emulated/0/Android/data/com.livetracker.controller/files/Download")
                if (appDir.exists() || appDir.mkdirs()) {
                    appDir
                } else {
                    File(System.getProperty("java.io.tmpdir", "."), "Android/data/com.livetracker.controller/files/Download").apply { mkdirs() }
                }
            }
        }
    }

    fun promptDownload(item: RemoteFileItem) {
        if (uiState.value.downloadLocation == DownloadLocationType.ALWAYS_ASK) {
            _uiState.update { it.copy(downloadLocationPrompt = DownloadLocationPrompt.Single(item)) }
        } else {
            requestDownloadFile(item, getEffectiveDownloadDestination())
        }
    }

    fun dismissDownloadPrompt() {
        _uiState.update { it.copy(downloadLocationPrompt = null) }
    }

    fun setDownloadLocation(loc: DownloadLocationType) {
        _uiState.update { it.copy(downloadLocation = loc) }
    }

    fun setTransfersSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showTransfersSheet = visible) }
    }

    fun clearCompletedTransfers() {
        val devId = _uiState.value.deviceId
        if (devId.isNotEmpty()) {
            fileTransferRepository.clearCompletedTransfers(TrackerId(devId))
        }
        _uiState.update { it.copy(downloadedFiles = emptyList()) }
    }

    fun downloadFile(item: RemoteFileItem, destinationDir: File = getEffectiveDownloadDestination()) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return
        if (item.isDirectory) {
            downloadSelectedFiles(destinationDir, listOf(item))
            return
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                destinationDir.mkdirs()
                val targetFile = File(destinationDir, item.name)
                _uiState.update { 
                    it.copy(
                        successMessage = "Downloading ${item.name}...",
                        downloadLocationPrompt = null
                    ) 
                }
                val result = fileTransferRepository.downloadFile(TrackerId(devId), item.path, targetFile, session)
                if (result.isSuccess) {
                    val transferId = result.getOrNull()!!
                    val finalTransfer = fileTransferRepository.observeFileTransferState(TrackerId(devId))
                        .mapNotNull { state -> state.activeTransfers.find { it.transferId == transferId } }
                        .first { it.status == TransferStatus.COMPLETED || it.status == TransferStatus.ERROR || it.status == TransferStatus.CANCELLED }

                    if (finalTransfer.status == TransferStatus.COMPLETED && targetFile.exists()) {
                        val record = DownloadedFileRecord(
                            fileName = item.name,
                            localPath = targetFile.absolutePath,
                            sizeBytes = targetFile.length()
                        )
                        _uiState.update { 
                            it.copy(
                                successMessage = "Downloaded ${item.name}",
                                downloadedFiles = listOf(record) + it.downloadedFiles
                            ) 
                        }
                    } else {
                        _uiState.update { it.copy(errorMessage = finalTransfer.errorMessage ?: "Download failed") }
                    }
                } else {
                    _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Download failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Download failed") }
            }
        }
    }

    fun downloadFileToUri(item: RemoteFileItem, targetUri: android.net.Uri, context: android.content.Context) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty() || item.isDirectory) return

        viewModelScope.launch(ioDispatcher) {
            val tempFile = File(context.cacheDir, "temp_dl_${System.currentTimeMillis()}_${item.name}")
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                _uiState.update { 
                    it.copy(
                        successMessage = "Downloading ${item.name}...",
                        downloadLocationPrompt = null
                    ) 
                }
                val result = fileTransferRepository.downloadFile(TrackerId(devId), item.path, tempFile, session)
                if (result.isSuccess) {
                    val transferId = result.getOrNull()!!
                    val finalTransfer = fileTransferRepository.observeFileTransferState(TrackerId(devId))
                        .mapNotNull { state -> state.activeTransfers.find { it.transferId == transferId } }
                        .first { it.status == TransferStatus.COMPLETED || it.status == TransferStatus.ERROR || it.status == TransferStatus.CANCELLED }

                    if (finalTransfer.status == TransferStatus.COMPLETED && tempFile.exists() && tempFile.length() > 0) {
                        context.contentResolver.openOutputStream(targetUri)?.use { out ->
                            tempFile.inputStream().use { input -> input.copyTo(out) }
                            out.flush()
                        }
                        val record = DownloadedFileRecord(
                            fileName = item.name,
                            localPath = targetUri.toString(),
                            sizeBytes = tempFile.length()
                        )
                        _uiState.update { 
                            it.copy(
                                successMessage = "Saved ${item.name} to chosen folder",
                                downloadedFiles = listOf(record) + it.downloadedFiles
                            ) 
                        }
                    } else {
                        _uiState.update { it.copy(errorMessage = finalTransfer.errorMessage ?: "Download failed") }
                    }
                } else {
                    _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Download failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Download failed") }
            } finally {
                try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    fun uploadFile(sourceFile: File) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return

        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                val targetDir = _uiState.value.currentPath.ifEmpty { "/storage/emulated/0/Download" }
                _uiState.update { it.copy(successMessage = "Uploading ${sourceFile.name}...") }
                val result = fileTransferRepository.uploadFile(TrackerId(devId), targetDir, sourceFile, session)
                if (result.isSuccess) {
                    val transferId = result.getOrNull()!!
                    val finalTransfer = fileTransferRepository.observeFileTransferState(TrackerId(devId))
                        .mapNotNull { state -> state.activeTransfers.find { it.transferId == transferId } }
                        .first { it.status == TransferStatus.COMPLETED || it.status == TransferStatus.ERROR || it.status == TransferStatus.CANCELLED }

                    if (finalTransfer.status == TransferStatus.COMPLETED) {
                        _uiState.update { it.copy(successMessage = "Successfully uploaded ${sourceFile.name}") }
                        refreshFiles(_uiState.value.currentPath)
                    } else {
                        _uiState.update { it.copy(errorMessage = finalTransfer.errorMessage ?: "Upload failed") }
                    }
                } else {
                    _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Upload failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Upload failed") }
            }
        }
    }

    fun deleteFile(item: RemoteFileItem) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return

        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                _uiState.update { it.copy(successMessage = "Deleting ${item.name}...") }
                val result = fileTransferRepository.deleteFile(TrackerId(devId), item.path, session)
                if (result.isSuccess) {
                    _uiState.update { it.copy(successMessage = "Deleted ${item.name}") }
                    refreshFiles(_uiState.value.currentPath)
                } else {
                    _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Delete failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Delete failed") }
            }
        }
    }

    fun setCategory(category: FileCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun toggleViewMode() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun cancelTransfer(transferId: String) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return
        viewModelScope.launch {
            fileTransferRepository.cancelTransfer(TrackerId(devId), transferId)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun clearError() {
        clearMessages()
    }

    private var batchDownloadJob: Job? = null

    fun toggleSelectionMode(enabled: Boolean? = null) {
        _uiState.update { state ->
            val newMode = enabled ?: !state.isSelectionMode
            state.copy(
                isSelectionMode = newMode,
                selectedFilePaths = if (!newMode) emptySet() else state.selectedFilePaths
            )
        }
    }

    fun toggleFileSelection(path: String) {
        _uiState.update { state ->
            val newSelected = state.selectedFilePaths.toMutableSet()
            if (newSelected.contains(path)) {
                newSelected.remove(path)
            } else {
                newSelected.add(path)
            }
            state.copy(
                isSelectionMode = true,
                selectedFilePaths = newSelected
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val selectablePaths = state.displayFiles
                .map { it.path }
                .toSet()
            state.copy(
                isSelectionMode = true,
                selectedFilePaths = selectablePaths
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(isSelectionMode = false, selectedFilePaths = emptySet()) }
    }

    fun setShowBatchProgressDialog(show: Boolean) {
        _uiState.update { it.copy(showBatchProgressDialog = show) }
    }

    fun cancelBatchDownload() {
        batchDownloadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                batchDownloadState = state.batchDownloadState.copy(
                    isActive = false,
                    isCompleted = true,
                    hasErrors = true,
                    items = state.batchDownloadState.items.map {
                        if (it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.QUEUED) {
                            it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user")
                        } else it
                    }
                )
            )
        }
    }

    fun requestDownloadFile(item: RemoteFileItem, destinationDir: File = getEffectiveDownloadDestination()) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return

        val targetFile = File(destinationDir, item.name)
        if (targetFile.exists()) {
            _uiState.update {
                it.copy(
                    fileConflictPrompt = FileConflictPrompt.Single(
                        item = item,
                        destinationDir = destinationDir,
                        existingSizeBytes = if (targetFile.isDirectory) 0L else targetFile.length()
                    )
                )
            }
            return
        }

        downloadFile(item, destinationDir)
    }

    fun confirmReplaceSingleFile(item: RemoteFileItem, destinationDir: File = getEffectiveDownloadDestination()) {
        _uiState.update { it.copy(fileConflictPrompt = null) }
        downloadFile(item, destinationDir)
    }

    fun requestDownloadSelectedFiles(destinationDir: File? = null, filesToDownload: List<RemoteFileItem>? = null) {
        val devId = _uiState.value.deviceId
        val selectedFiles = filesToDownload ?: run {
            val selectedPaths = _uiState.value.selectedFilePaths
            if (devId.isEmpty() || selectedPaths.isEmpty()) return
            _uiState.value.files.filter { it.path in selectedPaths }
        }
        if (selectedFiles.isEmpty()) return

        if (destinationDir == null && _uiState.value.downloadLocation == DownloadLocationType.ALWAYS_ASK) {
            _uiState.update { it.copy(downloadLocationPrompt = DownloadLocationPrompt.Batch(selectedFiles)) }
            return
        }

        val targetDir = destinationDir ?: getEffectiveDownloadDestination()

        val conflictingFiles = selectedFiles.filter { File(targetDir, it.name).exists() }
        if (conflictingFiles.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    fileConflictPrompt = FileConflictPrompt.Batch(
                        conflictingFiles = conflictingFiles,
                        allSelectedFiles = selectedFiles,
                        destinationDir = targetDir
                    )
                )
            }
            return
        }

        downloadSelectedFiles(targetDir, selectedFiles)
    }

    fun confirmDownloadBatchDefault(items: List<RemoteFileItem>) {
        _uiState.update { it.copy(downloadLocationPrompt = null) }
        requestDownloadSelectedFiles(destinationDir = getEffectiveDownloadDestination(), filesToDownload = items)
    }

    fun confirmDownloadBatchCustom(items: List<RemoteFileItem>, destinationDir: File) {
        _uiState.update { it.copy(downloadLocationPrompt = null) }
        requestDownloadSelectedFiles(destinationDir = destinationDir, filesToDownload = items)
    }

    fun confirmReplaceAllBatch(allFiles: List<RemoteFileItem>, destinationDir: File = getEffectiveDownloadDestination()) {
        _uiState.update { it.copy(fileConflictPrompt = null) }
        downloadSelectedFiles(destinationDir, allFiles)
    }

    fun confirmSkipExistingBatch(nonConflictingFiles: List<RemoteFileItem>, destinationDir: File = getEffectiveDownloadDestination()) {
        _uiState.update { it.copy(fileConflictPrompt = null) }
        if (nonConflictingFiles.isEmpty()) {
            _uiState.update {
                it.copy(
                    isSelectionMode = false,
                    selectedFilePaths = emptySet(),
                    successMessage = "All selected files already exist. Skipped."
                )
            }
            return
        }
        downloadSelectedFiles(destinationDir, nonConflictingFiles)
    }

    fun dismissFileConflictPrompt() {
        _uiState.update { it.copy(fileConflictPrompt = null) }
    }

    fun downloadSelectedFiles(destinationDir: File = getEffectiveDownloadDestination(), filesToDownload: List<RemoteFileItem>? = null) {
        val devId = _uiState.value.deviceId
        val selectedFiles = filesToDownload ?: run {
            val selectedPaths = _uiState.value.selectedFilePaths
            if (devId.isEmpty() || selectedPaths.isEmpty()) return
            _uiState.value.files.filter { it.path in selectedPaths }
        }
        if (devId.isEmpty() || selectedFiles.isEmpty()) return

        val initialItems = selectedFiles.map { file ->
            BatchDownloadItemProgress(
                file = file,
                bytesTransferred = 0L,
                totalBytes = file.sizeBytes,
                status = TransferStatus.QUEUED
            )
        }

        _uiState.update { state ->
            state.copy(
                isSelectionMode = false,
                selectedFilePaths = emptySet(),
                showBatchProgressDialog = true,
                batchDownloadState = BatchDownloadState(
                    isActive = true,
                    items = initialItems,
                    completedCount = 0,
                    totalCount = initialItems.size,
                    isCompleted = false,
                    hasErrors = false
                )
            )
        }

        batchDownloadJob?.cancel()
        batchDownloadJob = viewModelScope.launch(ioDispatcher) {
            val session = sessionRepository.getActiveSession().getOrNull()
            destinationDir.mkdirs()

            val completedPaths = mutableSetOf<String>()

            // High-Speed Batch Archive Strategy:
            // For multiple files or any directory, pack into high-speed ZIP archive batch
            val hasDirectories = selectedFiles.any { it.isDirectory }
            if (selectedFiles.size > 1 || hasDirectories) {
                val chunks = mutableListOf<List<RemoteFileItem>>()
                var currentChunk = mutableListOf<RemoteFileItem>()
                var currentChunkSize = 0L

                for (file in selectedFiles) {
                    if (currentChunk.size >= 40 || (currentChunkSize + file.sizeBytes > 12 * 1024 * 1024 && currentChunk.isNotEmpty())) {
                        chunks.add(currentChunk)
                        currentChunk = mutableListOf()
                        currentChunkSize = 0L
                    }
                    currentChunk.add(file)
                    currentChunkSize += file.sizeBytes
                }
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk)
                }

                for (chunk in chunks) {
                    chunk.forEach { item ->
                        updateBatchItem(item.path) { it.copy(status = TransferStatus.IN_PROGRESS) }
                    }

                    val chunkResult = fileTransferRepository.downloadFilesBatch(
                        deviceId = TrackerId(devId),
                        remotePaths = chunk.map { it.path },
                        destinationDir = destinationDir,
                        session = session,
                        onProgress = { entryPath, bytesRead, totalBytes ->
                            val matching = chunk.find { item ->
                                if (item.isDirectory) {
                                    entryPath == item.name || entryPath.startsWith(item.name + "/") || entryPath.startsWith(item.name + "\\")
                                } else {
                                    item.name == entryPath || entryPath.endsWith("/${item.name}") || entryPath.endsWith("\\${item.name}")
                                }
                            }
                            if (matching != null) {
                                updateBatchItem(matching.path) {
                                    it.copy(
                                        bytesTransferred = bytesRead,
                                        totalBytes = if (totalBytes > 0) totalBytes else it.totalBytes,
                                        status = TransferStatus.IN_PROGRESS
                                    )
                                }
                            }
                        }
                    ) { entryPath, sizeBytes ->
                        val matching = chunk.find { item ->
                            if (item.isDirectory) {
                                entryPath == item.name || entryPath.startsWith(item.name + "/") || entryPath.startsWith(item.name + "\\")
                            } else {
                                item.name == entryPath || entryPath.endsWith("/${item.name}") || entryPath.endsWith("\\${item.name}")
                            }
                        }
                        if (matching != null) {
                            completedPaths.add(matching.path)
                            val targetFile = File(destinationDir, entryPath)
                            updateBatchItem(matching.path) {
                                val updatedBytes = if (it.file.isDirectory) it.bytesTransferred + sizeBytes else sizeBytes
                                it.copy(
                                    bytesTransferred = updatedBytes,
                                    totalBytes = if (updatedBytes > it.totalBytes) updatedBytes else it.totalBytes,
                                    status = TransferStatus.COMPLETED
                                )
                            }
                            val record = DownloadedFileRecord(
                                fileName = matching.name,
                                localPath = targetFile.absolutePath,
                                sizeBytes = sizeBytes
                            )
                            _uiState.update { state ->
                                val alreadyCounted = state.downloadedFiles.any { it.fileName == matching.name && it.localPath == targetFile.absolutePath }
                                val newCompletedCount = if (alreadyCounted) state.batchDownloadState.completedCount else state.batchDownloadState.completedCount + 1
                                state.copy(
                                    downloadedFiles = if (alreadyCounted) state.downloadedFiles else listOf(record) + state.downloadedFiles,
                                    batchDownloadState = state.batchDownloadState.copy(
                                        completedCount = newCompletedCount
                                    )
                                )
                            }
                        }
                    }

                    if (chunkResult.isSuccess) {
                        chunk.forEach { item ->
                            completedPaths.add(item.path)
                            updateBatchItem(item.path) {
                                if (it.status != TransferStatus.COMPLETED) {
                                    it.copy(
                                        status = TransferStatus.COMPLETED,
                                        bytesTransferred = if (it.totalBytes > 0) it.totalBytes else it.bytesTransferred
                                    )
                                } else it
                            }
                        }
                        _uiState.update { state ->
                            val doneCount = state.batchDownloadState.items.count { it.status == TransferStatus.COMPLETED }
                            state.copy(
                                batchDownloadState = state.batchDownloadState.copy(completedCount = doneCount)
                            )
                        }
                    }
                }
            }

            // Fallback / Remaining files that weren't completed via batch archive
            val remainingFiles = selectedFiles.filter { it.path !in completedPaths && !it.isDirectory }
            if (remainingFiles.isNotEmpty()) {
                val avgSize = remainingFiles.sumOf { it.sizeBytes } / remainingFiles.size
                val permits = when {
                    avgSize < 250 * 1024 -> 6 // Small files: 6 parallel workers
                    avgSize < 1024 * 1024 -> 3 // Medium files: 3 parallel workers
                    else -> 1 // Large files: 1 permit to protect mobile uplink
                }
                val semaphore = Semaphore(permits = permits)

                coroutineScope {
                    remainingFiles.forEach { item ->
                        launch {
                            semaphore.withPermit {
                                downloadSingleBatchItem(item, destinationDir, devId, session)
                            }
                        }
                    }
                }
            }

            _uiState.update { state ->
                val anyErrors = state.batchDownloadState.items.any { it.status == TransferStatus.ERROR || it.status == TransferStatus.CANCELLED }
                val successfulCount = state.batchDownloadState.completedCount
                state.copy(
                    batchDownloadState = state.batchDownloadState.copy(
                        isActive = false,
                        isCompleted = true,
                        hasErrors = anyErrors
                    ),
                    successMessage = "Batch download finished ($successfulCount/${selectedFiles.size} completed)"
                )
            }
        }
    }

    private suspend fun downloadSingleBatchItem(
        item: RemoteFileItem,
        destinationDir: File,
        devId: String,
        session: com.livetracker.controller.core.auth.domain.Session?
    ) {
        val targetFile = File(destinationDir, item.name)
        updateBatchItem(item.path) { it.copy(status = TransferStatus.IN_PROGRESS) }

        try {
            val result = fileTransferRepository.downloadFile(TrackerId(devId), item.path, targetFile, session)
            if (result.isSuccess) {
                val transferId = result.getOrNull()!!
                fileTransferRepository.observeFileTransferState(TrackerId(devId))
                    .mapNotNull { state -> state.activeTransfers.find { it.transferId == transferId } }
                    .transformWhile { progress ->
                        updateBatchItem(item.path) {
                            it.copy(
                                bytesTransferred = progress.bytesTransferred,
                                totalBytes = if (progress.totalBytes > 0) progress.totalBytes else it.totalBytes,
                                status = progress.status,
                                errorMessage = progress.errorMessage
                            )
                        }
                        emit(progress)
                        progress.status != TransferStatus.COMPLETED &&
                            progress.status != TransferStatus.ERROR &&
                            progress.status != TransferStatus.CANCELLED
                    }
                    .collect()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Download failed"
                updateBatchItem(item.path) { it.copy(status = TransferStatus.ERROR, errorMessage = err) }
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                updateBatchItem(item.path) { it.copy(status = TransferStatus.ERROR, errorMessage = e.message ?: "Failed") }
            }
        }

        val finalItem = _uiState.value.batchDownloadState.items.find { it.file.path == item.path }
        if (finalItem?.status == TransferStatus.COMPLETED && targetFile.exists() && targetFile.length() > 0) {
            val record = DownloadedFileRecord(
                fileName = item.name,
                localPath = targetFile.absolutePath,
                sizeBytes = targetFile.length()
            )
            _uiState.update { state ->
                state.copy(
                    downloadedFiles = listOf(record) + state.downloadedFiles,
                    batchDownloadState = state.batchDownloadState.copy(
                        completedCount = state.batchDownloadState.completedCount + 1
                    )
                )
            }
        }
    }

    private fun updateBatchItem(path: String, transform: (BatchDownloadItemProgress) -> BatchDownloadItemProgress) {
        _uiState.update { state ->
            val newItems = state.batchDownloadState.items.map {
                if (it.file.path == path) transform(it) else it
            }
            state.copy(batchDownloadState = state.batchDownloadState.copy(items = newItems))
        }
    }
}
