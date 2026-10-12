package com.livetracker.controller.core.update.downloader

import com.livetracker.controller.core.update.provider.UpdateNetworkException
import com.livetracker.controller.core.update.provider.UpdateSecurityException
import com.livetracker.controller.core.update.provider.UpdateTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production implementation of [UpdateDownloader] streaming bytes safely to a temporary staging file.
 * Automatically cleans up incomplete artifacts upon failure or cancellation.
 */
class HttpUpdateDownloader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UpdateDownloader {

    private val isCancelledFlag = AtomicBoolean(false)
    private var activeConnection: HttpURLConnection? = null

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 16 * 1024 // 16 KB streaming buffer
    }

    override fun cancel() {
        isCancelledFlag.set(true)
        try {
            activeConnection?.disconnect()
        } catch (_: Throwable) {}
    }

    override suspend fun downloadApk(
        url: String,
        targetFile: File,
        expectedSizeBytes: Long,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progressPercent: Int) -> Unit
    ): Result<File> = withContext(ioDispatcher) {
        isCancelledFlag.set(false)

        val targetUrl = URL(url)
        val isLoopback = targetUrl.host == "localhost" || targetUrl.host == "127.0.0.1"
        if (targetUrl.protocol != "https" && !isLoopback) {
            return@withContext Result.failure(
                UpdateSecurityException("Insecure HTTP APK download rejected. HTTPS is required.")
            )
        }

        // Storage check
        val parentDir = targetFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        val usableSpace = parentDir.usableSpace
        if (usableSpace > 0 && expectedSizeBytes > 0 && usableSpace < expectedSizeBytes) {
            return@withContext Result.failure(
                IOException("Insufficient storage space: $usableSpace bytes available, $expectedSizeBytes bytes required.")
            )
        }

        val partFile = File("${targetFile.absolutePath}.part")
        if (partFile.exists()) {
            partFile.delete()
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (targetUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                setRequestProperty("User-Agent", "Trace-Android-Controller-Updater/1.0")
            }
            activeConnection = connection

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IOException("HTTP $responseCode received while downloading APK")
                )
            }

            val serverContentLength = connection.contentLengthLong
            val totalLength = if (serverContentLength > 0) serverContentLength else expectedSizeBytes

            connection.inputStream.use { inputStream ->
                FileOutputStream(partFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalDownloaded = 0L
                    var lastReportedPercent = -1
                    var lastReportedTime = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelledFlag.get()) {
                            partFile.delete()
                            throw CancellationException("Download was cancelled by user")
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val currentPercent = if (totalLength > 0) {
                            ((totalDownloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                        } else 0

                        // Throttle progress emissions: emit every 1% or at least every 100ms
                        if (currentPercent != lastReportedPercent || (now - lastReportedTime > 100)) {
                            lastReportedPercent = currentPercent
                            lastReportedTime = now
                            onProgress(totalDownloaded, totalLength, currentPercent)
                        }
                    }
                    outputStream.flush()

                    if (expectedSizeBytes > 0 && totalDownloaded != expectedSizeBytes) {
                        partFile.delete()
                        return@withContext Result.failure(
                            IOException("Downloaded APK size ($totalDownloaded bytes) does not match expected size ($expectedSizeBytes bytes)")
                        )
                    }

                    // Final 100% emission
                    onProgress(totalDownloaded, totalLength, 100)
                }
            }

            // Atomic rename from staging .part to target file
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = partFile.renameTo(targetFile)
            if (!renamed) {
                // Fallback copy if filesystem cross-volume rename fails
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            Result.success(targetFile)
        } catch (e: CancellationException) {
            partFile.delete()
            Result.failure(e)
        } catch (e: UnknownHostException) {
            partFile.delete()
            Result.failure(UpdateNetworkException("Host could not be resolved during download", e))
        } catch (e: SocketTimeoutException) {
            partFile.delete()
            Result.failure(UpdateTimeoutException("Network timeout during APK download", e))
        } catch (e: Exception) {
            partFile.delete()
            Result.failure(e)
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }
}
