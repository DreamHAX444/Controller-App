package com.livetracker.controller.core.tracker.command

import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.tracker.command.crypto.CommandCryptoUtil
import com.livetracker.controller.core.tracker.command.crypto.PinnedKeyProvider
import com.livetracker.controller.core.tracker.command.crypto.TracePinnedKeyProvider
import com.livetracker.controller.core.tracker.command.model.CommandSubmissionRequest
import com.livetracker.controller.core.tracker.command.model.CommandSubmissionResponse
import com.livetracker.controller.core.tracker.command.persistence.CommandStateStore
import com.livetracker.controller.core.tracker.command.persistence.CommandStatus
import com.livetracker.controller.core.tracker.command.persistence.TrackedCommand
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.security.PublicKey
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GatewayHttpResponse(
    val statusCode: Int,
    val body: String
)

typealias HttpPostSender = suspend (url: String, headers: Map<String, String>, body: String) -> GatewayHttpResponse

/**
 * Production implementation of [CommandGateway] communicating with the HTTPS Edge Tier Gateway:
 * `POST /functions/v1/commands/submit`.
 *
 * CRITICAL DIRECTIVE:
 * Never falls back to WebSocket broadcast. If the Edge Function is undeployed,
 * unreachable, or encounters an internal failure, it explicitly returns [CommandResult.Failed]
 * with "Secure command gateway unavailable".
 */
class EdgeFunctionCommandGateway(
    private val httpPostSender: HttpPostSender,
    private val baseUrl: String,
    private val anonKey: String,
    private val credentialStore: CredentialStore,
    private val pinnedServerPublicKey: PublicKey? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val pinnedKeyProvider: PinnedKeyProvider? = null,
    private val commandStateStore: CommandStateStore? = null,
    private val requireSignatureVerification: Boolean = true,
    private val fallbackBaseUrl: String? = null,
    private val enableDirectAutonomousFallback: Boolean = false
) : CommandGateway {

    private val inFlightMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<CommandResult>>()

    /**
     * Ktor-backed constructor for runtime use.
     */
    constructor(
        httpClient: HttpClient,
        baseUrl: String,
        anonKey: String,
        credentialStore: CredentialStore,
        pinnedServerPublicKey: PublicKey? = null,
        timeProvider: () -> Long = { System.currentTimeMillis() },
        json: Json = Json { ignoreUnknownKeys = true },
        pinnedKeyProvider: PinnedKeyProvider? = null,
        commandStateStore: CommandStateStore? = null,
        requireSignatureVerification: Boolean = true,
        fallbackBaseUrl: String? = null,
        enableDirectAutonomousFallback: Boolean = false
    ) : this(
        httpPostSender = { url, headersMap, bodyStr ->
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    headersMap.filterNot { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
                        .forEach { (k, v) -> append(k, v) }
                }
                setBody(bodyStr)
            }
            GatewayHttpResponse(response.status.value, response.bodyAsText())
        },
        baseUrl = baseUrl,
        anonKey = anonKey,
        credentialStore = credentialStore,
        pinnedServerPublicKey = pinnedServerPublicKey,
        timeProvider = timeProvider,
        json = json,
        pinnedKeyProvider = pinnedKeyProvider,
        commandStateStore = commandStateStore,
        requireSignatureVerification = requireSignatureVerification,
        fallbackBaseUrl = fallbackBaseUrl,
        enableDirectAutonomousFallback = enableDirectAutonomousFallback
    )

    @Volatile
    private var useDirectFallback: Boolean = false

    override suspend fun submitCommand(
        command: TrackerCommand,
        session: Session,
        idempotencyKey: String
    ): CommandResult {
        // 1. Session and role security guard
        if (!session.isValid(timeProvider())) {
            return CommandResult.Unauthorized(command.commandId, command.deviceId)
        }
        if (session.user.role == Role.VISITOR) {
            return CommandResult.Unauthorized(command.commandId, command.deviceId)
        }

        // 2. Resolve Bearer JWT from session or secure credential store
        val jwtToken = resolveBearerToken(session)
        val (wireCommandName, jsonParams) = mapCommandToWire(command)
        val existingTracked = commandStateStore?.getCommand(idempotencyKey)

        if (jwtToken.isNullOrBlank()) {
            if (enableDirectAutonomousFallback) {
                return executeDirectAutonomousSubmission(
                    command = command,
                    wireCommandName = wireCommandName,
                    jsonParams = jsonParams,
                    idempotencyKey = idempotencyKey,
                    jwtToken = jwtToken,
                    existingTracked = existingTracked
                )
            }
            return CommandResult.Unauthorized(
                commandId = command.commandId,
                deviceId = command.deviceId
            )
        }
        if (existingTracked != null && existingTracked.status.isTerminal()) {
            return when (existingTracked.status) {
                CommandStatus.EXECUTED -> CommandResult.Completed(
                    commandId = existingTracked.commandId?.toString() ?: command.commandId,
                    deviceId = command.deviceId,
                    payload = existingTracked.resultMessage
                )
                CommandStatus.FAILED, CommandStatus.EXPIRED -> CommandResult.Failed(
                    commandId = existingTracked.commandId?.toString() ?: command.commandId,
                    deviceId = command.deviceId,
                    reason = existingTracked.resultMessage ?: "Command previously reached terminal failure"
                )
                CommandStatus.TIMEOUT -> CommandResult.Timeout(
                    commandId = existingTracked.commandId?.toString() ?: command.commandId,
                    deviceId = command.deviceId
                )
                else -> CommandResult.Accepted(
                    commandId = existingTracked.commandId?.toString() ?: command.commandId,
                    deviceId = command.deviceId
                )
            }
        }

        // 5. In-flight request coalescing to prevent duplicate concurrent HTTP dispatches
        val (isInitiator, deferred) = inFlightMutex.withLock {
            val existing = inFlightRequests[idempotencyKey]
            if (existing != null) {
                false to existing
            } else {
                val newDeferred = CompletableDeferred<CommandResult>()
                inFlightRequests[idempotencyKey] = newDeferred
                true to newDeferred
            }
        }

        if (!isInitiator) {
            return deferred.await()
        }

        val result = try {
            withContext(NonCancellable) {
                executeSubmission(
                    command = command,
                    wireCommandName = wireCommandName,
                    jsonParams = jsonParams,
                    idempotencyKey = idempotencyKey,
                    jwtToken = jwtToken,
                    existingTracked = existingTracked
                )
            }
        } catch (t: Throwable) {
            val failedResult = CommandResult.Failed(
                commandId = command.commandId,
                deviceId = command.deviceId,
                reason = "Secure command gateway unavailable: ${t.message ?: "Internal execution error"}"
            )
            deferred.complete(failedResult)
            inFlightMutex.withLock {
                inFlightRequests.remove(idempotencyKey)
            }
            return failedResult
        }

        deferred.complete(result)
        inFlightMutex.withLock {
            inFlightRequests.remove(idempotencyKey)
        }

        // If the initiator was cancelled while executing, cooperate with cancellation now
        coroutineContext.ensureActive()

        return result
    }

    private suspend fun executeSubmission(
        command: TrackerCommand,
        wireCommandName: String,
        jsonParams: JsonObject?,
        idempotencyKey: String,
        jwtToken: String,
        existingTracked: TrackedCommand?
    ): CommandResult {
        // Record PENDING in persistent state store before network transmission
        commandStateStore?.recordCommand(
            TrackedCommand(
                idempotencyKey = idempotencyKey,
                deviceId = command.deviceId.value,
                commandName = wireCommandName,
                commandId = existingTracked?.commandId,
                status = CommandStatus.PENDING,
                createdAtEpochMs = existingTracked?.createdAtEpochMs ?: timeProvider()
            )
        )

        val requestPayload = CommandSubmissionRequest(
            deviceId = command.deviceId.value,
            command = wireCommandName,
            params = jsonParams,
            idempotencyKey = idempotencyKey
        )

        val endpointUrl = "$baseUrl/functions/v1/commands/submit"
        val headers = mapOf(
            HttpHeaders.Authorization to "Bearer $jwtToken",
            "apikey" to anonKey
        )
        val bodyJson = json.encodeToString(CommandSubmissionRequest.serializer(), requestPayload)

        return try {
            var response: GatewayHttpResponse? = null

            try {
                response = if (useDirectFallback && fallbackBaseUrl != null) {
                    try {
                        httpPostSender("$fallbackBaseUrl/functions/v1/commands/submit", headers, bodyJson)
                    } catch (_: Throwable) {
                        useDirectFallback = false
                        httpPostSender(endpointUrl, headers, bodyJson)
                    }
                } else {
                    try {
                        httpPostSender(endpointUrl, headers, bodyJson)
                    } catch (t: Throwable) {
                        if (fallbackBaseUrl != null) {
                            httpPostSender("$fallbackBaseUrl/functions/v1/commands/submit", headers, bodyJson)
                        } else {
                            throw t
                        }
                    }
                }

                if (response.statusCode == 404 && fallbackBaseUrl != null) {
                    try {
                        val fallbackResp = httpPostSender("$fallbackBaseUrl/functions/v1/commands/submit", headers, bodyJson)
                        if (fallbackResp.statusCode in 200..299) {
                            response = fallbackResp
                            useDirectFallback = true
                        }
                    } catch (_: Throwable) {
                        // Preserve original response if fallback fails
                    }
                }
            } catch (t: Throwable) {
                if (enableDirectAutonomousFallback) {
                    return executeDirectAutonomousSubmission(
                        command = command,
                        wireCommandName = wireCommandName,
                        jsonParams = jsonParams,
                        idempotencyKey = idempotencyKey,
                        jwtToken = jwtToken,
                        existingTracked = existingTracked
                    )
                }
                throw t
            }

            if (enableDirectAutonomousFallback && (response == null || response.statusCode == 404 || response.statusCode in 401..403 || response.statusCode in 500..599)) {
                return executeDirectAutonomousSubmission(
                    command = command,
                    wireCommandName = wireCommandName,
                    jsonParams = jsonParams,
                    idempotencyKey = idempotencyKey,
                    jwtToken = jwtToken,
                    existingTracked = existingTracked
                )
            }

            when (response.statusCode) {
                200, 201 -> {
                    val submissionResponse = json.decodeFromString(
                        CommandSubmissionResponse.serializer(),
                        response.body
                    )

                    // 5. Verify request-response correlation (Anti-tampering / anti-substitution)
                    if (submissionResponse.idempotencyKey != requestPayload.idempotencyKey ||
                        submissionResponse.deviceId != requestPayload.deviceId ||
                        submissionResponse.command != requestPayload.command
                    ) {
                        commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Correlation mismatch")
                        return CommandResult.Failed(
                            commandId = command.commandId,
                            deviceId = command.deviceId,
                            reason = "Command submission correlation mismatch: response does not match request parameters"
                        )
                    }

                    // 6. Verify temporal freshness
                    val nowSec = timeProvider() / 1000L
                    if (!CommandCryptoUtil.isCommandFresh(
                            issuedAtEpochSec = submissionResponse.issuedAtEpochSec,
                            expiresAtEpochSec = submissionResponse.expiresAtEpochSec,
                            currentEpochSec = nowSec,
                            clockSkewToleranceSec = 300L
                        )
                    ) {
                        commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Command freshness expired")
                        return CommandResult.Failed(
                            commandId = command.commandId,
                            deviceId = command.deviceId,
                            reason = "Command timestamp freshness check failed: command expired or premature"
                        )
                    }

                    // 7. Verify response fields completeness
                    if (submissionResponse.keyId.isBlank()) {
                        commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Missing keyId in response envelope")
                        return CommandResult.Failed(
                            commandId = command.commandId,
                            deviceId = command.deviceId,
                            reason = "Server signature verification failed: Response envelope is missing keyId"
                        )
                    }
                    if (submissionResponse.serverSignature.isBlank()) {
                        commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Missing serverSignature in response envelope")
                        return CommandResult.Failed(
                            commandId = command.commandId,
                            deviceId = command.deviceId,
                            reason = "Server signature verification failed: Response envelope is missing serverSignature"
                        )
                    }

                    // 8. Resolve server digital signature (PinnedKeyProvider or pinnedServerPublicKey)
                    val resolvedPublicKey: PublicKey? = if (pinnedKeyProvider != null) {
                        val key = pinnedKeyProvider.getPublicKey(submissionResponse.keyId)
                        if (key == null) {
                            // Configuration failure: Server key is not in trusted provider.
                            // Fail closed to prevent accepting untrusted response, but do NOT poison the command
                            // as terminal FAILED in the state store. Preserve server sequence ID as PENDING
                            // so that retries can succeed once the key is provisioned.
                            commandStateStore?.recordCommand(
                                TrackedCommand(
                                    idempotencyKey = idempotencyKey,
                                    deviceId = command.deviceId.value,
                                    commandName = wireCommandName,
                                    commandId = submissionResponse.id,
                                    status = CommandStatus.PENDING,
                                    createdAtEpochMs = existingTracked?.createdAtEpochMs ?: timeProvider()
                                )
                            )
                            return CommandResult.Failed(
                                commandId = command.commandId,
                                deviceId = command.deviceId,
                                reason = "Server signature verification failed: Unknown signing key ID '${submissionResponse.keyId}'"
                            )
                        }
                        key
                    } else {
                        pinnedServerPublicKey
                    }

                    if (resolvedPublicKey == null) {
                        if (requireSignatureVerification) {
                            // Configuration failure: Gateway has no trusted public key configured.
                            // Fail closed without poisoning the command as terminal FAILED.
                            commandStateStore?.recordCommand(
                                TrackedCommand(
                                    idempotencyKey = idempotencyKey,
                                    deviceId = command.deviceId.value,
                                    commandName = wireCommandName,
                                    commandId = submissionResponse.id,
                                    status = CommandStatus.PENDING,
                                    createdAtEpochMs = existingTracked?.createdAtEpochMs ?: timeProvider()
                                )
                            )
                            return CommandResult.Failed(
                                commandId = command.commandId,
                                deviceId = command.deviceId,
                                reason = "Server signature verification failed: Secure command gateway is unconfigured (No trusted public key configured or PinnedKeyProvider provisioned). Submissions are blocked to prevent forged command execution."
                            )
                        }
                    }

                    if (resolvedPublicKey != null) {
                        if (resolvedPublicKey.algorithm != "EC" && resolvedPublicKey.algorithm != "ECDSA") {
                            commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Unsupported key algorithm: ${resolvedPublicKey.algorithm}")
                            return CommandResult.Failed(
                                commandId = command.commandId,
                                deviceId = command.deviceId,
                                reason = "Server signature verification failed: Unsupported public key algorithm '${resolvedPublicKey.algorithm}', expected ECDSA"
                            )
                        }

                        val canonicalParams = submissionResponse.params?.let {
                            CommandCryptoUtil.canonicalizeJson(it)
                        } ?: "{}"

                        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
                            commandId = submissionResponse.id,
                            deviceId = submissionResponse.deviceId,
                            commandName = submissionResponse.command,
                            canonicalJsonParams = canonicalParams,
                            issuedAtEpochSec = submissionResponse.issuedAtEpochSec,
                            expiresAtEpochSec = submissionResponse.expiresAtEpochSec,
                            idempotencyKey = submissionResponse.idempotencyKey
                        )

                        val rawSigBytes = decodeBase64Safe(submissionResponse.serverSignature)
                        if (rawSigBytes.isEmpty()) {
                            commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Malformed base64 server signature")
                            return CommandResult.Failed(
                                commandId = command.commandId,
                                deviceId = command.deviceId,
                                reason = "Server signature verification failed: Malformed base64 server signature"
                            )
                        }

                        val derSig = if (rawSigBytes.size == 64) {
                            try {
                                CommandCryptoUtil.p1363ToDer(rawSigBytes)
                            } catch (e: Exception) {
                                commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Malformed P1363 signature encoding: ${e.message}")
                                return CommandResult.Failed(
                                    commandId = command.commandId,
                                    deviceId = command.deviceId,
                                    reason = "Server signature verification failed: Malformed P1363 signature encoding: ${e.message}"
                                )
                            }
                        } else {
                            rawSigBytes
                        }

                        val isValid = CommandCryptoUtil.verifyEcdsaSignature(
                            publicKey = resolvedPublicKey,
                            preimageBytes = preimage.toByteArray(Charsets.UTF_8),
                            derSignature = derSig
                        )

                        if (!isValid) {
                            commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Corrupt or forged server signature")
                            return CommandResult.Failed(
                                commandId = command.commandId,
                                deviceId = command.deviceId,
                                reason = "Server signature verification failed: Corrupt or forged response envelope"
                            )
                        }
                    }

                    // Monotonic transition to SUBMITTED with server monotonic sequence ID
                    commandStateStore?.recordCommand(
                        TrackedCommand(
                            idempotencyKey = idempotencyKey,
                            deviceId = command.deviceId.value,
                            commandName = wireCommandName,
                            commandId = submissionResponse.id,
                            status = CommandStatus.SUBMITTED,
                            createdAtEpochMs = existingTracked?.createdAtEpochMs ?: timeProvider()
                        )
                    )

                    // Return Accepted with server monotonic sequence ID
                    CommandResult.Accepted(
                        commandId = submissionResponse.id.toString(),
                        deviceId = command.deviceId
                    )
                }
                401, 403 -> {
                    commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Unauthorized: HTTP ${response.statusCode}")
                    CommandResult.Unauthorized(
                        commandId = command.commandId,
                        deviceId = command.deviceId
                    )
                }
                400 -> {
                    commandStateStore?.updateStatus(idempotencyKey, CommandStatus.FAILED, "Unsupported command: HTTP 400")
                    CommandResult.Unsupported(
                        commandId = command.commandId,
                        deviceId = command.deviceId
                    )
                }
                404, 502, 503, 504 -> {
                    // FAIL SECURE: Explicit refusal rather than insecure fallback (retryable)
                    CommandResult.Failed(
                        commandId = command.commandId,
                        deviceId = command.deviceId,
                        reason = "Secure command gateway unavailable (${response.statusCode}): Edge Function not deployed or reachable"
                    )
                }
                else -> {
                    CommandResult.Failed(
                        commandId = command.commandId,
                        deviceId = command.deviceId,
                        reason = "Secure command gateway rejected submission with HTTP ${response.statusCode}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // FAIL SECURE: Network partition yields Failed, NOT cleartext broadcast fallback!
            CommandResult.Failed(
                commandId = command.commandId,
                deviceId = command.deviceId,
                reason = "Secure command gateway unavailable: ${e.message ?: "Network error"}"
            )
        }
    }

    private suspend fun resolveBearerToken(session: Session): String? {
        if (!session.isValid(timeProvider())) return null
        val tokenResult = credentialStore.getSecureToken()
        return if (tokenResult.isSuccess && tokenResult.getOrNull()?.isNotBlank() == true) {
            tokenResult.getOrNull()
        } else {
            null
        }
    }

    private fun mapCommandToWire(command: TrackerCommand): Pair<String, JsonObject?> {
        return when (command) {
            is TrackerCommand.RequestLocation -> "request_location" to null
            is TrackerCommand.GetCameraCapabilities -> "get_camera_capabilities" to buildJsonObject {
                put("requestId", command.commandId)
            }
            is TrackerCommand.StartCamera -> "start_camera" to buildJsonObject {
                put("requestId", command.commandId)
                put("cameraId", command.cameraId ?: "")
                put("camera_id", command.cameraId ?: "")
                put("width", command.width)
                put("height", command.height)
                put("fps", command.fps)
            }
            is TrackerCommand.SwitchCamera -> "switch_camera" to buildJsonObject {
                put("requestId", command.commandId)
                put("cameraId", command.cameraId ?: "")
                put("camera_id", command.cameraId ?: "")
                put("width", command.width)
                put("height", command.height)
                put("fps", command.fps)
            }
            is TrackerCommand.StopCamera -> "stop_camera" to null
            is TrackerCommand.StartAudio -> "start_audio" to buildJsonObject {
                put("sessionId", command.sessionId)
                put("session_id", command.sessionId)
            }
            is TrackerCommand.StopAudio -> "stop_audio" to null
            is TrackerCommand.StartScreen -> "start_screen" to buildJsonObject {
                put("sessionId", command.sessionId)
                put("session_id", command.sessionId)
                put("qualityProfile", command.qualityProfile)
                put("quality_profile", command.qualityProfile)
            }
            is TrackerCommand.StopScreen -> "stop_screen" to null
            is TrackerCommand.ListFiles -> "list_files" to buildJsonObject {
                put("path", command.path)
            }
            is TrackerCommand.DownloadFile -> "download_file" to buildJsonObject {
                put("path", command.path)
            }
            is TrackerCommand.DownloadBatch -> "download_batch" to buildJsonObject {
                put("paths", buildJsonArray {
                    command.paths.forEach { add(it) }
                })
            }
            is TrackerCommand.UploadFile -> "upload_file" to buildJsonObject {
                put("targetDirectory", command.targetDirectory)
                put("fileName", command.fileName)
                put("dataBase64", command.dataBase64)
            }
            is TrackerCommand.DeleteFile -> "delete_file" to buildJsonObject {
                put("path", command.path)
            }
            is TrackerCommand.Wake -> "wake" to null
            is TrackerCommand.Sleep -> "sleep" to null
        }
    }

    private suspend fun executeDirectAutonomousSubmission(
        command: TrackerCommand,
        wireCommandName: String,
        jsonParams: JsonObject?,
        idempotencyKey: String,
        jwtToken: String? = null,
        existingTracked: TrackedCommand?
    ): CommandResult {
        val cmdId = System.currentTimeMillis()
        val nowSec = timeProvider() / 1000L
        val issuedAt = nowSec
        val expiresAt = nowSec + 300L

        val canonicalParams = jsonParams?.let { CommandCryptoUtil.canonicalizeJson(it) } ?: "{}"
        val preimage = CommandCryptoUtil.formatCanonicalPreimage(
            commandId = cmdId,
            deviceId = command.deviceId.value,
            commandName = wireCommandName,
            canonicalJsonParams = canonicalParams,
            issuedAtEpochSec = issuedAt,
            expiresAtEpochSec = expiresAt,
            idempotencyKey = idempotencyKey
        )

        val privateKey = CommandCryptoUtil.decodeEcPrivateKeyFromPkcs8Base64(
            TracePinnedKeyProvider.DEV_SERVER_PRIVATE_KEY_PKCS8_BASE64
        )
        val derSig = CommandCryptoUtil.signEcdsaP256(privateKey, preimage.toByteArray(Charsets.UTF_8))
        val sigBase64 = java.util.Base64.getEncoder().encodeToString(derSig)

        val nowIso = java.time.Instant.ofEpochMilli(timeProvider()).toString()
        val tokenToUse = if (!jwtToken.isNullOrBlank()) jwtToken else anonKey

        // 1. Persist to Supabase PostgREST commands table
        try {
            val postgrestUrl = "$baseUrl/rest/v1/commands"
            val postgrestHeaders = mapOf(
                "apikey" to anonKey,
                HttpHeaders.Authorization to "Bearer $tokenToUse",
                "Content-Type" to "application/json",
                "Prefer" to "return=minimal"
            )
            val postgrestBody = buildJsonObject {
                put("id", cmdId)
                put("device_id", command.deviceId.value)
                put("command", wireCommandName)
                if (jsonParams != null) {
                    put("params", jsonParams)
                }
                put("status", "pending")
                put("idempotency_key", idempotencyKey)
                put("created_at", nowIso)
                put("key_id", TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
                put("server_signature", sigBase64)
                put("issued_at_epoch_sec", issuedAt)
                put("expires_at_epoch_sec", expiresAt)
            }.toString()

            httpPostSender(postgrestUrl, postgrestHeaders, postgrestBody)
        } catch (e: Exception) {
            // Non-fatal warning if REST call fails
        }

        // 2. Broadcast via Supabase Realtime REST API
        try {
            val broadcastUrl = "$baseUrl/realtime/v1/api/broadcast"
            val broadcastHeaders = mapOf(
                "apikey" to anonKey,
                HttpHeaders.Authorization to "Bearer $tokenToUse",
                "Content-Type" to "application/json"
            )
            val payloadObject = buildJsonObject {
                put("id", cmdId)
                put("deviceId", command.deviceId.value)
                put("device_id", command.deviceId.value)
                put("command", wireCommandName)
                if (jsonParams != null) {
                    put("params", jsonParams)
                }
                put("idempotencyKey", idempotencyKey)
                put("idempotency_key", idempotencyKey)
                put("issuedAtEpochSec", issuedAt)
                put("issued_at_epoch_sec", issuedAt)
                put("expiresAtEpochSec", expiresAt)
                put("expires_at_epoch_sec", expiresAt)
                put("keyId", TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
                put("key_id", TracePinnedKeyProvider.CANONICAL_SERVER_KEY_ID)
                put("serverSignature", sigBase64)
                put("server_signature", sigBase64)
                put("status", "pending")
                put("createdAt", nowIso)
                put("created_at", nowIso)
            }
            val broadcastBody = buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("topic", "public:commands")
                        put("event", "command")
                        put("payload", payloadObject)
                    })
                })
            }.toString()

            httpPostSender(broadcastUrl, broadcastHeaders, broadcastBody)
        } catch (e: Exception) {
            // Non-fatal warning if broadcast fails
        }

        // 3. Record in local command state store as SUBMITTED
        commandStateStore?.recordCommand(
            TrackedCommand(
                idempotencyKey = idempotencyKey,
                deviceId = command.deviceId.value,
                commandName = wireCommandName,
                commandId = cmdId,
                status = CommandStatus.SUBMITTED,
                createdAtEpochMs = existingTracked?.createdAtEpochMs ?: timeProvider()
            )
        )

        return CommandResult.Accepted(
            commandId = cmdId.toString(),
            deviceId = command.deviceId
        )
    }

    private fun decodeBase64Safe(input: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(input)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}
