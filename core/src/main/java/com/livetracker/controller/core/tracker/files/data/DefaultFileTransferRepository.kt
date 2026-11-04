package com.livetracker.controller.core.tracker.files.data

import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.files.domain.FileTransferRepository
import com.livetracker.controller.core.tracker.files.domain.FileTransferState
import com.livetracker.controller.core.tracker.files.domain.RemoteFileItem
import com.livetracker.controller.core.tracker.files.domain.TransferProgress
import com.livetracker.controller.core.tracker.files.domain.TransferStatus
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import io.github.jan.supabase.postgrest.postgrest
import com.livetracker.controller.core.tracker.supabase.ServerCommandStatusRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import kotlinx.serialization.json.JsonPrimitive

import com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel

class DefaultFileTransferRepository(
    private val webRtcSessionManager: WebRtcSessionManager? = null,
    private val sessionManager: TrackerSessionManager? = null,
    private val authEngine: AuthorizationEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    commandsChannel: RealtimeChannel? = null,
    private val context: android.content.Context? = null
) : FileTransferRepository {

    private val statesFlow = MutableStateFlow<Map<TrackerId, FileTransferState>>(emptyMap())
    private val mutex = Mutex()
    private val pendingListRequests = ConcurrentHashMap<String, CompletableDeferred<List<RemoteFileItem>>>()
    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
    val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<CommandAckEnvelope>>()

    private var activeCommandsChannel: RealtimeChannel? = commandsChannel
    private var ackSubscriptionJob: Job? = null

    init {
        if (commandsChannel != null) {
            bindCommandsChannel(commandsChannel)
        } else {
            ensureChannelSubscription()
        }
    }

    fun bindCommandsChannel(channel: RealtimeChannel) {
        activeCommandsChannel = channel
        ackSubscriptionJob?.cancel()
        ackSubscriptionJob = scope.launch {
            try {
                if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED && channel.status.value != RealtimeChannel.Status.SUBSCRIBING) {
                    try { channel.subscribe(blockUntilSubscribed = false) } catch (_: Exception) {}
                }
                channel.broadcastFlow<CommandAckEnvelope>("command_ack").collect { ack ->
                    val deferred = pendingAcks.remove(ack.idempotencyKey)
                        ?: ack.commandId?.let { pendingAcks.remove(it) }
                        ?: ack.sequenceId?.let { pendingAcks.remove(it.toString()) }
                    deferred?.complete(ack)
                }
            } catch (e: Exception) {
                android.util.Log.w("DefaultFileTransferRepo", "command_ack flow error: ${e.message}")
            }
        }
    }

    fun handleCommandAck(ack: CommandAckEnvelope) {
        val deferred = pendingAcks.remove(ack.idempotencyKey)
            ?: ack.commandId?.let { pendingAcks.remove(it) }
            ?: ack.sequenceId?.let { pendingAcks.remove(it.toString()) }
        deferred?.complete(ack)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun awaitCommandPayload(
        cmdId: String,
        existingDeferred: CompletableDeferred<CommandAckEnvelope>? = null,
        timeoutMs: Long = 30_000L
    ): String? {
        val ackDeferred = existingDeferred ?: pendingAcks.getOrPut(cmdId) { CompletableDeferred() }
        val startTime = System.currentTimeMillis()
        var lastDbPollTime = 0L
        try {
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (ackDeferred.isCompleted) {
                    val ack = ackDeferred.getCompleted()
                    val st = ack.status.lowercase()
                    if (st == "completed" || st == "executed") {
                        return ack.resultPayload
                    } else if (st == "failed") {
                        throw Exception(ack.errorMessage ?: "Tracker reported command failed")
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastDbPollTime >= 3000L) {
                    lastDbPollTime = now
                    try {
                        val client = SupabaseClientProvider.client
                        val resp = client.postgrest["commands"].select {
                            filter { eq("idempotency_key", cmdId) }
                            limit(1)
                        }.decodeList<ServerCommandStatusRecord>().firstOrNull()
                        if (resp != null) {
                            val st = resp.status.lowercase()
                            if (st == "executed" || st == "completed") {
                                val elem = resp.resultPayload
                                val text = if (elem is JsonPrimitive && elem.isString) elem.content else elem?.toString()
                                if (!text.isNullOrBlank()) {
                                    return text
                                }
                            } else if (st == "failed") {
                                val errText = (resp.resultPayload as? JsonPrimitive)?.content ?: "Command failed"
                                throw Exception("Tracker reported command failed: $errText")
                            }
                        }
                    } catch (e: Exception) {
                        if (e.message?.contains("Tracker reported command failed") == true) throw e
                    }
                }
                delay(200)
            }
        } finally {
            pendingAcks.remove(cmdId)
        }
        return null
    }

    private fun ensureChannelSubscription() {
        if (activeCommandsChannel != null && ackSubscriptionJob?.isActive == true) return
        try {
            val ch = SupabaseClientProvider.client.channel("public:commands")
            bindCommandsChannel(ch)
        } catch (_: Throwable) {
            // Offline or in unit test environment
        }
    }

    private class ActiveDownload(
        val transferId: String,
        val targetFile: File,
        val outputStream: FileOutputStream,
        val digest: MessageDigest,
        val completion: CompletableDeferred<Result<String>>,
        var bytesReceived: Long = 0L,
        var totalBytes: Long = 0L
    )

    override fun observeFileTransferState(deviceId: TrackerId): Flow<FileTransferState> {
        return statesFlow.map { map ->
            map[deviceId] ?: FileTransferState(deviceId = deviceId)
        }.distinctUntilChanged()
    }

    override suspend fun getFileTransferState(deviceId: TrackerId): FileTransferState? {
        return mutex.withLock { statesFlow.value[deviceId] }
    }

    override suspend fun listFiles(
        deviceId: TrackerId,
        path: String,
        session: Session?
    ): Result<List<RemoteFileItem>> = mutex.withLock {
        // Step 1: Authorization Gate
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.FILE_READ,
                action = Action.READ,
                currentTime = timeProvider()
            )
            val fallbackAuth = if (authResult !is AuthorizationResult.Allowed) {
                authEngine.authorize(
                    session = session,
                    deviceId = deviceId.value,
                    service = ServicePermission.FILE_TRANSFER,
                    action = Action.READ,
                    currentTime = timeProvider()
                )
            } else authResult

            if (fallbackAuth !is AuthorizationResult.Allowed) {
                val err = "File access denied: ${fallbackAuth::class.simpleName}"
                updateState(deviceId) { it.copy(isLoading = false, errorMessage = err) }
                return Result.failure(SecurityException(err))
            }
        }

        updateState(deviceId) { it.copy(isLoading = true, currentPath = path, errorMessage = null) }

        // Step 2: Check for active DataChannel
        val dataChannel = findFileTransferDataChannel(deviceId)
        if (dataChannel != null && dataChannel.state.value == com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.OPEN) {
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<List<RemoteFileItem>>()
            pendingListRequests[requestId] = deferred

            val jsonMsg = """{"type":"listFiles","transferId":"$requestId","path":"$path"}"""
            dataChannel.send(jsonMsg)

            val result = withTimeoutOrNull(5000L) {
                deferred.await()
            }
            pendingListRequests.remove(requestId)

            if (result != null) {
                updateState(deviceId) {
                    it.copy(
                        files = result,
                        isLoading = false,
                        lastUpdated = timeProvider()
                    )
                }
                return Result.success(result)
            }
        }

        // Step 3: Fallback to direct Command Gateway if DataChannel is not open
        if (sessionManager != null) {
            val cmdId = UUID.randomUUID().toString()
            val command = com.livetracker.controller.core.tracker.domain.TrackerCommand.ListFiles(
                commandId = cmdId,
                deviceId = deviceId,
                path = path,
                actorId = session?.user?.id ?: "controller"
            )
            try {
                ensureChannelSubscription()
                val cmdResult = sessionManager.sendCommand(command)
                var payloadToParse: String? = null
                if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Completed && !cmdResult.payload.isNullOrBlank()) {
                    payloadToParse = cmdResult.payload
                } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Accepted) {
                    payloadToParse = awaitCommandPayload(cmdId, timeoutMs = 15_000L)
                }

                if (!payloadToParse.isNullOrBlank()) {
                    val parsed = parseFileListPayload(payloadToParse)
                    val effectivePath = try {
                        val obj = Json.parseToJsonElement(payloadToParse).jsonObject
                        obj["path"]?.jsonPrimitive?.content ?: path
                    } catch (_: Exception) { path }

                    if (parsed != null) {
                        updateState(deviceId) {
                            it.copy(
                                files = parsed,
                                isLoading = false,
                                currentPath = effectivePath,
                                errorMessage = null,
                                lastUpdated = timeProvider()
                            )
                        }
                        return Result.success(parsed)
                    }
                }
            } catch (e: Exception) {
                // Fall through to error reporting
            }
        }

        val errorMsg = "File listing failed: Device ${deviceId.value} offline or unreachable"
        updateState(deviceId) {
            it.copy(
                isLoading = false,
                errorMessage = errorMsg,
                lastUpdated = timeProvider()
            )
        }
        return Result.failure(IllegalStateException(errorMsg))
    }

    private fun parseFileListPayload(payload: String): List<RemoteFileItem>? {
        return try {
            val jsonElement = Json.parseToJsonElement(payload)
            val jsonObject = jsonElement.jsonObject
            val filesArray = jsonObject["files"]?.jsonArray ?: return null
            filesArray.map { item ->
                val obj = item.jsonObject
                RemoteFileItem(
                    id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    path = obj["path"]?.jsonPrimitive?.content ?: "",
                    sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    modifiedTimeMillis = obj["modifiedTimeMillis"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    isDirectory = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false,
                    mimeType = obj["mimeType"]?.jsonPrimitive?.content,
                    readable = obj["readable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    writable = obj["writable"]?.jsonPrimitive?.booleanOrNull ?: true
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun downloadFile(
        deviceId: TrackerId,
        remotePath: String,
        targetFile: File,
        session: Session?
    ): Result<String> {
        // Step 1: Authorization Gate
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.FILE_TRANSFER,
                action = Action.READ,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                return Result.failure(SecurityException("Download access denied"))
            }
        }

        val transferId = UUID.randomUUID().toString()
        val fileName = remotePath.substringAfterLast('/', remotePath)
        val progress = TransferProgress(
            transferId = transferId,
            fileName = fileName,
            remotePath = remotePath,
            status = TransferStatus.IN_PROGRESS
        )

        updateState(deviceId) {
            it.copy(activeTransfers = it.activeTransfers + progress)
        }

        val dataChannel = findFileTransferDataChannel(deviceId)
        if (dataChannel != null && dataChannel.state.value == com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.OPEN) {
            val completion = CompletableDeferred<Result<String>>()
            targetFile.parentFile?.mkdirs()
            val fos = FileOutputStream(targetFile)
            val digest = MessageDigest.getInstance("SHA-256")
            val active = ActiveDownload(transferId, targetFile, fos, digest, completion)
            activeDownloads[transferId] = active

            // Send startDownload request to Tracker
            val jsonMsg = """{"type":"startDownload","transferId":"$transferId","path":"$remotePath"}"""
            dataChannel.send(jsonMsg)

            scope.launch {
                val outcome = withTimeoutOrNull(60_000L) {
                    completion.await()
                } ?: Result.failure(java.util.concurrent.TimeoutException("Transfer timed out"))
                
                activeDownloads.remove(transferId)?.let {
                    try { it.outputStream.close() } catch (_: Exception) {}
                }
                updateState(deviceId) { state ->
                    state.copy(
                        activeTransfers = state.activeTransfers.map {
                            if (it.transferId == transferId) {
                                it.copy(
                                    bytesTransferred = if (outcome.isSuccess) targetFile.length() else it.bytesTransferred,
                                    totalBytes = if (outcome.isSuccess) targetFile.length() else it.totalBytes,
                                    status = if (outcome.isSuccess) TransferStatus.COMPLETED else TransferStatus.ERROR,
                                    errorMessage = outcome.exceptionOrNull()?.message
                                )
                            } else it
                        }
                    )
                }
            }
            return Result.success(transferId)
        } else if (sessionManager != null) {
            // Fallback to Command Gateway if DataChannel is not open
            val cmdId = UUID.randomUUID().toString()
            val command = com.livetracker.controller.core.tracker.domain.TrackerCommand.DownloadFile(
                commandId = cmdId,
                deviceId = deviceId,
                path = remotePath,
                actorId = session?.user?.id ?: "controller"
            )
            scope.launch(Dispatchers.IO) {
                try {
                    ensureChannelSubscription()
                    val cmdResult = sessionManager.sendCommand(command)
                    var payloadToParse: String? = null
                    if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Completed && !cmdResult.payload.isNullOrBlank()) {
                        payloadToParse = cmdResult.payload
                    } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Accepted) {
                        payloadToParse = awaitCommandPayload(cmdId, timeoutMs = 60_000L)
                    } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Failed) {
                        throw Exception(cmdResult.reason)
                    } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Unauthorized) {
                        throw SecurityException("Command unauthorized")
                    } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Timeout) {
                        throw Exception("Command submission timed out")
                    }

                    if (!payloadToParse.isNullOrBlank()) {
                        android.util.Log.i("DefaultFileTransferRepo", "payloadToParse: $payloadToParse")
                        val jsonElem = Json.parseToJsonElement(payloadToParse).jsonObject
                        val success = jsonElem["success"]?.jsonPrimitive?.booleanOrNull ?: false
                        val storageUrl = jsonElem["storageUrl"]?.jsonPrimitive?.content
                        val base64Data = jsonElem["dataBase64"]?.jsonPrimitive?.content
                        val sizeBytes = jsonElem["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L
                        if (success && (!storageUrl.isNullOrBlank() || !base64Data.isNullOrBlank())) {
                            targetFile.parentFile?.mkdirs()
                            if (!storageUrl.isNullOrBlank()) {
                                updateState(deviceId) { state ->
                                    state.copy(
                                        activeTransfers = state.activeTransfers.map {
                                            if (it.transferId == transferId) {
                                                it.copy(
                                                    totalBytes = sizeBytes,
                                                    status = TransferStatus.IN_PROGRESS
                                                )
                                            } else it
                                        }
                                    )
                                }
                                downloadFileFromStorage(storageUrl, targetFile, sizeBytes) { bytesRead, totalBytes ->
                                    updateState(deviceId) { state ->
                                        state.copy(
                                            activeTransfers = state.activeTransfers.map {
                                                if (it.transferId == transferId) {
                                                    it.copy(
                                                        bytesTransferred = bytesRead,
                                                        totalBytes = totalBytes.coerceAtLeast(bytesRead),
                                                        status = TransferStatus.IN_PROGRESS
                                                    )
                                                } else it
                                            }
                                        )
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    deleteStorageObject(storageUrl)
                                }
                            } else {
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                targetFile.writeBytes(bytes)
                                if (context != null) {
                                    try {
                                        android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                                    } catch (_: Exception) {}
                                }
                            }
                            updateState(deviceId) { state ->
                                state.copy(
                                    activeTransfers = state.activeTransfers.map {
                                        if (it.transferId == transferId) {
                                            it.copy(
                                                bytesTransferred = targetFile.length(),
                                                totalBytes = targetFile.length(),
                                                status = TransferStatus.COMPLETED
                                            )
                                        } else it
                                    }
                                )
                            }
                        } else {
                            val err = jsonElem["error"]?.jsonPrimitive?.content ?: "Download failed"
                            throw Exception(err)
                        }
                    } else {
                        throw Exception("Timeout waiting for tracker download response")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DefaultFileTransferRepo", "downloadFile failure for $remotePath", e)
                    updateState(deviceId) { state ->
                        state.copy(
                            activeTransfers = state.activeTransfers.map {
                                if (it.transferId == transferId) {
                                    it.copy(
                                        status = TransferStatus.ERROR,
                                        errorMessage = e.message ?: "Download failed"
                                    )
                                } else it
                            }
                        )
                    }
                }
            }
            return Result.success(transferId)
        } else {
            val errorMsg = "File transfer failed: DataChannel 'file-transfer' is not open for device ${deviceId.value}"
            updateState(deviceId) { state ->
                state.copy(
                    activeTransfers = state.activeTransfers.map {
                        if (it.transferId == transferId) {
                            it.copy(
                                status = TransferStatus.ERROR,
                                errorMessage = errorMsg
                            )
                        } else it
                    }
                )
            }
            return Result.failure(IllegalStateException(errorMsg))
        }
    }

    override suspend fun downloadFilesBatch(
        deviceId: TrackerId,
        remotePaths: List<String>,
        destinationDir: File,
        session: Session?,
        onProgress: (fileName: String, bytesRead: Long, totalBytes: Long) -> Unit,
        onFileExtracted: (fileName: String, sizeBytes: Long) -> Unit
    ): Result<Int> {
        if (remotePaths.isEmpty()) return Result.success(0)

        // Step 1: Authorization Gate
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.FILE_TRANSFER,
                action = Action.READ,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                return Result.failure(SecurityException("Download access denied"))
            }
        }

        if (sessionManager == null) {
            return Result.failure(IllegalStateException("Tracker session manager not available"))
        }

        val cmdId = UUID.randomUUID().toString()
        val command = com.livetracker.controller.core.tracker.domain.TrackerCommand.DownloadBatch(
            commandId = cmdId,
            deviceId = deviceId,
            paths = remotePaths,
            actorId = session?.user?.id ?: "controller"
        )

        ensureChannelSubscription()
        val cmdResult = sessionManager.sendCommand(command)
        var payloadToParse: String? = null
        if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Completed && !cmdResult.payload.isNullOrBlank()) {
            payloadToParse = cmdResult.payload
        } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Accepted) {
            payloadToParse = awaitCommandPayload(cmdId, timeoutMs = 60_000L)
        } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Failed) {
            return Result.failure(Exception(cmdResult.reason))
        } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Unauthorized) {
            return Result.failure(SecurityException("Batch command unauthorized"))
        } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Timeout) {
            return Result.failure(Exception("Batch command submission timed out"))
        }

        if (payloadToParse.isNullOrBlank()) {
            return Result.failure(Exception("Timeout waiting for tracker batch download response"))
        }

        val jsonElem = Json.parseToJsonElement(payloadToParse).jsonObject
        val success = jsonElem["success"]?.jsonPrimitive?.booleanOrNull ?: false
        val storageUrl = jsonElem["storageUrl"]?.jsonPrimitive?.content
        val base64Data = jsonElem["dataBase64"]?.jsonPrimitive?.content
        if (!success || (storageUrl.isNullOrBlank() && base64Data.isNullOrBlank())) {
            val err = jsonElem["error"]?.jsonPrimitive?.content ?: "Batch download failed"
            return Result.failure(Exception(err))
        }

        destinationDir.mkdirs()
        var extractedCount = 0

        val inputStream: java.io.InputStream = if (!storageUrl.isNullOrBlank()) {
            val url = java.net.URL(storageUrl)
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return Result.failure(IllegalStateException("Batch storage download failed with HTTP $code"))
            }
            java.io.BufferedInputStream(conn.inputStream)
        } else {
            val zipBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            java.io.ByteArrayInputStream(zipBytes)
        }

        inputStream.use { rawIn ->
            java.util.zip.ZipInputStream(rawIn).use { zis ->
                var entry = zis.nextEntry
                val buffer = ByteArray(16384)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryPath = entry.name.replace('\\', '/')
                        val normalized = java.io.File(entryPath).normalize().path.replace('\\', '/').trimStart('/')
                        if (!normalized.startsWith("../") && !normalized.contains("/../") && normalized.isNotBlank()) {
                            val targetFile = File(destinationDir, normalized)
                            targetFile.parentFile?.mkdirs()
                            var entryBytes = 0L
                            var lastProgressTime = 0L
                            val entryTotal = entry.size.takeIf { it > 0 } ?: 0L

                            targetFile.outputStream().use { fos ->
                                var r = zis.read(buffer)
                                while (r != -1) {
                                    fos.write(buffer, 0, r)
                                    entryBytes += r
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressTime >= 100) {
                                        lastProgressTime = now
                                        onProgress(normalized, entryBytes, if (entryTotal > 0) entryTotal else entryBytes)
                                    }
                                    r = zis.read(buffer)
                                }
                                fos.flush()
                            }
                            onProgress(normalized, entryBytes, entryBytes)
                            extractedCount++
                            onFileExtracted(normalized, targetFile.length())
                            if (context != null) {
                                try {
                                    android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (!storageUrl.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                deleteStorageObject(storageUrl)
            }
        }

        return Result.success(extractedCount)
    }

    override suspend fun uploadFile(
        deviceId: TrackerId,
        targetDirectory: String,
        sourceFile: File,
        session: Session?
    ): Result<String> {
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.FILE_TRANSFER,
                action = Action.WRITE,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                return Result.failure(SecurityException("Upload access denied"))
            }
        }

        if (!sourceFile.exists() || !sourceFile.isFile) {
            return Result.failure(IllegalArgumentException("Source file does not exist: ${sourceFile.absolutePath}"))
        }

        val transferId = UUID.randomUUID().toString()
        val fileName = sourceFile.name
        val progress = TransferProgress(
            transferId = transferId,
            fileName = fileName,
            remotePath = "$targetDirectory/$fileName",
            totalBytes = sourceFile.length(),
            status = TransferStatus.IN_PROGRESS
        )

        updateState(deviceId) {
            it.copy(activeTransfers = it.activeTransfers + progress)
        }

        if (sessionManager != null) {
            val cmdId = UUID.randomUUID().toString()
            val bytes = sourceFile.readBytes()
            val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val command = com.livetracker.controller.core.tracker.domain.TrackerCommand.UploadFile(
                commandId = cmdId,
                deviceId = deviceId,
                targetDirectory = targetDirectory,
                fileName = fileName,
                dataBase64 = base64Data,
                actorId = session?.user?.id ?: "controller"
            )
            scope.launch(Dispatchers.IO) {
                try {
                    ensureChannelSubscription()
                    val ackDeferred = CompletableDeferred<CommandAckEnvelope>()
                    pendingAcks[cmdId] = ackDeferred

                    val cmdResult = sessionManager.sendCommand(command)
                    var payloadToParse: String? = null
                    if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Completed && !cmdResult.payload.isNullOrBlank()) {
                        pendingAcks.remove(cmdId)
                        payloadToParse = cmdResult.payload
                    } else if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Accepted) {
                        val ack = try {
                            withTimeoutOrNull(30_000L) {
                                ackDeferred.await()
                            }
                        } finally {
                            pendingAcks.remove(cmdId)
                        }

                        if (ack != null) {
                            val statusLower = ack.status.lowercase()
                            if (statusLower == "executed" || statusLower == "completed") {
                                val text = ack.resultPayload
                                if (!text.isNullOrBlank()) {
                                    payloadToParse = text
                                }
                            }
                        } else {
                            // Fallback gracefully only if WebSocket ack timed out
                            try {
                                val client = SupabaseClientProvider.client
                                val resp = client.postgrest["commands"].select {
                                    filter { eq("idempotency_key", cmdId) }
                                    limit(1)
                                }.decodeList<ServerCommandStatusRecord>().firstOrNull()
                                if (resp != null && resp.status.lowercase() == "executed") {
                                    val elem = resp.resultPayload
                                    val text = if (elem is JsonPrimitive && elem.isString) elem.content else elem?.toString()
                                    if (!text.isNullOrBlank()) {
                                        payloadToParse = text
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        pendingAcks.remove(cmdId)
                    }

                    if (!payloadToParse.isNullOrBlank()) {
                        val jsonElem = Json.parseToJsonElement(payloadToParse).jsonObject
                        val success = jsonElem["success"]?.jsonPrimitive?.booleanOrNull ?: false
                        if (success) {
                            updateState(deviceId) { state ->
                                state.copy(
                                    activeTransfers = state.activeTransfers.map {
                                        if (it.transferId == transferId) {
                                            it.copy(
                                                bytesTransferred = sourceFile.length(),
                                                totalBytes = sourceFile.length(),
                                                status = TransferStatus.COMPLETED
                                            )
                                        } else it
                                    }
                                )
                            }
                            listFiles(deviceId, targetDirectory, session)
                        } else {
                            val err = jsonElem["error"]?.jsonPrimitive?.content ?: "Upload failed"
                            throw Exception(err)
                        }
                    } else {
                        throw Exception("Timeout waiting for tracker upload response")
                    }
                } catch (e: Exception) {
                    updateState(deviceId) { state ->
                        state.copy(
                            activeTransfers = state.activeTransfers.map {
                                if (it.transferId == transferId) {
                                    it.copy(
                                        status = TransferStatus.ERROR,
                                        errorMessage = e.message ?: "Upload failed"
                                    )
                                } else it
                            }
                        )
                    }
                }
            }
            return Result.success(transferId)
        }

        val err = "Upload failed: Tracker session manager not available"
        updateState(deviceId) { state ->
            state.copy(
                activeTransfers = state.activeTransfers.map {
                    if (it.transferId == transferId) {
                        it.copy(status = TransferStatus.ERROR, errorMessage = err)
                    } else it
                }
            )
        }
        return Result.failure(IllegalStateException(err))
    }

    override suspend fun deleteFile(
        deviceId: TrackerId,
        remotePath: String,
        session: Session?
    ): Result<Unit> = mutex.withLock {
        if (sessionManager != null) {
            val cmdId = UUID.randomUUID().toString()
            val command = com.livetracker.controller.core.tracker.domain.TrackerCommand.DeleteFile(
                commandId = cmdId,
                deviceId = deviceId,
                path = remotePath,
                actorId = session?.user?.id ?: "controller"
            )
            val cmdResult = sessionManager.sendCommand(command)
            if (cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Accepted ||
                cmdResult is com.livetracker.controller.core.tracker.domain.CommandResult.Completed) {
                val parentDir = remotePath.substringBeforeLast('/', "")
                listFiles(deviceId, parentDir, session)
                return Result.success(Unit)
            }
        }
        return Result.failure(IllegalStateException("Failed to dispatch delete command"))
    }

    override suspend fun cancelTransfer(deviceId: TrackerId, transferId: String): Result<Unit> = mutex.withLock {
        activeDownloads.remove(transferId)?.let { active ->
            try { active.outputStream.close() } catch (_: Exception) {}
            active.completion.cancel()
        }

        val dataChannel = findFileTransferDataChannel(deviceId)
        if (dataChannel != null) {
            val jsonMsg = """{"type":"cancelTransfer","transferId":"$transferId"}"""
            dataChannel.send(jsonMsg)
        }

        updateState(deviceId) { state ->
            state.copy(
                activeTransfers = state.activeTransfers.map {
                    if (it.transferId == transferId) {
                        it.copy(status = TransferStatus.CANCELLED)
                    } else it
                }
            )
        }

        Result.success(Unit)
    }

    override fun clearCompletedTransfers(deviceId: TrackerId) {
        updateState(deviceId) { state ->
            state.copy(
                activeTransfers = state.activeTransfers.filter {
                    it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.QUEUED
                }
            )
        }
    }

    fun handleIncomingDataChannelMessage(deviceId: TrackerId, message: String) {
        try {
            val json = Json.parseToJsonElement(message).jsonObject
            val type = json["type"]?.jsonPrimitive?.content
            val transferId = json["transferId"]?.jsonPrimitive?.content

            when (type) {
                "fileList" -> {
                    val filesArray = json["files"]?.jsonArray
                    if (filesArray != null && transferId != null) {
                        val parsed = filesArray.mapNotNull { element ->
                            try {
                                val obj = element.jsonObject
                                RemoteFileItem(
                                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                                    path = obj["path"]?.jsonPrimitive?.content ?: "",
                                    sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                                    modifiedTimeMillis = obj["modifiedTimeMillis"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                                    isDirectory = obj["isDirectory"]?.jsonPrimitive?.booleanOrNull ?: false,
                                    mimeType = obj["mimeType"]?.jsonPrimitive?.content,
                                    readable = obj["readable"]?.jsonPrimitive?.booleanOrNull ?: true,
                                    writable = obj["writable"]?.jsonPrimitive?.booleanOrNull ?: false
                                )
                            } catch (_: Exception) { null }
                        }
                        pendingListRequests[transferId]?.complete(parsed)
                    }
                }
                "complete" -> {
                    if (transferId != null) {
                        val active = activeDownloads[transferId]
                        val expectedSha256 = json["sha256"]?.jsonPrimitive?.content
                        if (active != null) {
                            try {
                                active.outputStream.flush()
                                active.outputStream.close()
                                val computedSha256 = active.digest.digest().joinToString("") { "%02x".format(it) }
                                if (expectedSha256 != null && !expectedSha256.equals(computedSha256, ignoreCase = true)) {
                                    active.completion.complete(
                                        Result.failure(IllegalStateException("SHA-256 integrity check failed. Expected: $expectedSha256, got: $computedSha256"))
                                    )
                                } else {
                                    active.completion.complete(Result.success(transferId))
                                }
                            } catch (e: Exception) {
                                active.completion.complete(Result.failure(e))
                            }
                        } else {
                            activeDownloads[transferId]?.completion?.complete(Result.success(transferId))
                        }
                    }
                }
                "error" -> {
                    val err = json["message"]?.jsonPrimitive?.content ?: "File transfer failed"
                    if (transferId != null) {
                        activeDownloads[transferId]?.completion?.complete(Result.failure(Exception(err)))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun handleIncomingBinaryChunk(deviceId: TrackerId, bytes: ByteArray) {
        if (bytes.size < 2) return
        val type = bytes[0].toInt()
        if (type == 1) { // Binary chunk
            val idLength = bytes[1].toInt()
            val headerSize = 1 + 1 + idLength + 8
            if (bytes.size < headerSize) return

            val transferId = String(bytes, 2, idLength, Charsets.UTF_8)
            val chunk = bytes.copyOfRange(headerSize, bytes.size)

            val active = activeDownloads[transferId]
            if (active != null) {
                try {
                    active.outputStream.write(chunk)
                    active.digest.update(chunk)
                    active.bytesReceived += chunk.size

                    updateState(deviceId) { state ->
                        state.copy(
                            activeTransfers = state.activeTransfers.map {
                                if (it.transferId == transferId) {
                                    it.copy(bytesTransferred = active.bytesReceived)
                                } else it
                            }
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private val boundChannels = ConcurrentHashMap<TrackerId, Job>()

    private fun bindDataChannel(deviceId: TrackerId, channel: TrackerDataChannel) {
        if (boundChannels.containsKey(deviceId)) return
        val job = scope.launch {
            launch {
                channel.incomingMessages.collect { msg ->
                    handleIncomingDataChannelMessage(deviceId, msg)
                }
            }
            launch {
                channel.incomingBinaryMessages.collect { bytes ->
                    handleIncomingBinaryChunk(deviceId, bytes)
                }
            }
        }
        boundChannels[deviceId] = job
    }

    private fun findFileTransferDataChannel(deviceId: TrackerId): TrackerDataChannel? {
        val rtcSession = webRtcSessionManager?.getSession(deviceId) ?: return null
        val channel = rtcSession.getDataChannel("file-transfer")
        if (channel != null) {
            bindDataChannel(deviceId, channel)
            return channel
        }
        return null
    }

    private suspend fun downloadFileFromStorage(
        storageUrl: String,
        targetFile: File,
        expectedBytes: Long = 0L,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val conn = (java.net.URL(storageUrl).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("Storage download failed with HTTP $code")
        }
        val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
        targetFile.parentFile?.mkdirs()
        try { if (targetFile.exists()) targetFile.delete() } catch (_: Exception) {}

        conn.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                if (onProgress != null) {
                    val buffer = ByteArray(16384)
                    var total = 0L
                    var lastUpdate = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) {
                        output.write(buffer, 0, n)
                        total += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 100 || total == contentLength) {
                            lastUpdate = now
                            onProgress(total, contentLength.coerceAtLeast(total))
                        }
                    }
                } else {
                    input.copyTo(output)
                }
            }
        }
        conn.disconnect()
        onProgress?.invoke(targetFile.length(), contentLength.coerceAtLeast(targetFile.length()))
    }

    private suspend fun deleteStorageObject(storageUrl: String) = withContext(Dispatchers.IO) {
        val objectPath = storageUrl.substringAfter("/storage/v1/object/public/", "")
        if (objectPath.isBlank()) return@withContext
        try {
            val url = java.net.URL("${SupabaseClientProvider.supabaseUrl}/storage/v1/object/$objectPath")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("apikey", SupabaseClientProvider.supabaseKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseClientProvider.supabaseKey}")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun updateState(deviceId: TrackerId, transform: (FileTransferState) -> FileTransferState) {
        statesFlow.update { map ->
            val current = map[deviceId] ?: FileTransferState(deviceId = deviceId)
            map + (deviceId to transform(current))
        }
    }
}
