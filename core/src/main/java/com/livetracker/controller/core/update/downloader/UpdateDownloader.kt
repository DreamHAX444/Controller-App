package com.livetracker.controller.core.update.downloader

import java.io.File

/**
 * Interface for downloading the release APK artifact with progress updates and cancellation support.
 */
interface UpdateDownloader {
    /**
     * Downloads an APK from [url] into [targetFile].
     * Reports progress via [onProgress].
     */
    suspend fun downloadApk(
        url: String,
        targetFile: File,
        expectedSizeBytes: Long,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progressPercent: Int) -> Unit
    ): Result<File>

    /**
     * Cancels any active download operation.
     */
    fun cancel()
}
