package com.livetracker.controller.core.tracker.files.domain

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import java.io.File

interface FileTransferRepository {
    fun observeFileTransferState(deviceId: TrackerId): Flow<FileTransferState>
    suspend fun getFileTransferState(deviceId: TrackerId): FileTransferState?
    suspend fun listFiles(deviceId: TrackerId, path: String, session: Session?): Result<List<RemoteFileItem>>
    suspend fun downloadFile(deviceId: TrackerId, remotePath: String, targetFile: File, session: Session?): Result<String>
    suspend fun downloadFilesBatch(
        deviceId: TrackerId,
        remotePaths: List<String>,
        destinationDir: File,
        session: Session?,
        onProgress: (fileName: String, bytesRead: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        onFileExtracted: (fileName: String, sizeBytes: Long) -> Unit = { _, _ -> }
    ): Result<Int> = Result.failure(UnsupportedOperationException("Batch download not supported"))
    suspend fun uploadFile(deviceId: TrackerId, targetDirectory: String, sourceFile: File, session: Session?): Result<String>
    suspend fun deleteFile(deviceId: TrackerId, remotePath: String, session: Session?): Result<Unit>
    suspend fun cancelTransfer(deviceId: TrackerId, transferId: String): Result<Unit>
    fun clearCompletedTransfers(deviceId: TrackerId)
}
