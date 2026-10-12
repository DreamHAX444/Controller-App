package com.livetracker.controller.core.update.provider

import com.livetracker.controller.core.update.model.UpdateFailureReason
import com.livetracker.controller.core.update.model.UpdateManifest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Production implementation of [UpdateProvider] leveraging standard secure HTTPS connections.
 * Enforces strict HTTPS origins (with loopback allowed for local unit tests),
 * enforces connection/read timeouts, and caps response payload sizes to 1 MB.
 */
class HttpUpdateProvider(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
) : UpdateProvider {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_MANIFEST_SIZE_BYTES = 1024 * 1024 // 1 MB limit
    }

    override suspend fun fetchManifest(manifestUrl: String): Result<UpdateManifest> = withContext(ioDispatcher) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(manifestUrl)

            // Enforce HTTPS unless localhost/127.0.0.1 for testing
            val isLoopback = url.host == "localhost" || url.host == "127.0.0.1"
            if (url.protocol != "https" && !isLoopback) {
                return@withContext Result.failure(
                    UpdateSecurityException("Insecure HTTP endpoint rejected. HTTPS is mandatory.")
                )
            }

            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Trace-Android-Controller-Updater/1.0")
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val failureReason = when (statusCode) {
                    404 -> UpdateFailureReason.RESOURCE_NOT_FOUND
                    429 -> UpdateFailureReason.SERVER_ERROR
                    in 500..599 -> UpdateFailureReason.SERVER_ERROR
                    else -> UpdateFailureReason.SERVER_ERROR
                }
                val errorDetail = when (statusCode) {
                    404 -> "Update manifest not found (HTTP 404) at $manifestUrl"
                    429 -> "Rate limited by update server (HTTP 429)"
                    else -> "HTTP $statusCode from update server: $errorStream"
                }
                return@withContext Result.failure(
                    UpdateHttpException(
                        statusCode = statusCode,
                        failureReason = failureReason,
                        message = errorDetail
                    )
                )
            }

            val inputStream = connection.inputStream
            val buffer = ByteArray(4096)
            val outputStream = ByteArrayOutputStream()
            var bytesRead: Int
            var totalBytes = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytes += bytesRead
                if (totalBytes > MAX_MANIFEST_SIZE_BYTES) {
                    return@withContext Result.failure(
                        UpdateSecurityException("Update manifest payload exceeded maximum permitted size of $MAX_MANIFEST_SIZE_BYTES bytes")
                    )
                }
                outputStream.write(buffer, 0, bytesRead)
            }

            val jsonContent = outputStream.toString("UTF-8")
            val manifest = json.decodeFromString<UpdateManifest>(jsonContent)

            // Validate schema constraints
            if (manifest.schemaVersion > 1) {
                return@withContext Result.failure(
                    UpdateValidationException("Unsupported manifest schema version: ${manifest.schemaVersion}")
                )
            }

            Result.success(manifest)
        } catch (e: UnknownHostException) {
            Result.failure(UpdateNetworkException("Network unavailable or host unresolvable", e))
        } catch (e: SocketTimeoutException) {
            Result.failure(UpdateTimeoutException("Connection timed out while checking for updates", e))
        } catch (e: IOException) {
            Result.failure(UpdateNetworkException("I/O failure while fetching update manifest: ${e.message}", e))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}

class UpdateSecurityException(message: String) : SecurityException(message)
class UpdateValidationException(message: String) : IllegalArgumentException(message)
class UpdateNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)
class UpdateTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)
class UpdateHttpException(
    val statusCode: Int,
    val failureReason: UpdateFailureReason,
    message: String
) : IOException(message)
