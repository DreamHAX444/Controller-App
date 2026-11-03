package com.livetracker.controller.core.tracker.files.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ERROR
}

data class TransferProgress(
    val transferId: String,
    val fileName: String,
    val remotePath: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.IN_PROGRESS,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class FileTransferState(
    val deviceId: TrackerId,
    val currentPath: String = "",
    val files: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val activeTransfers: List<TransferProgress> = emptyList(),
    val errorMessage: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
