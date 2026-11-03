package com.livetracker.controller.core.tracker.files

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DefaultAuthorizationEngine
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.files.data.DefaultFileTransferRepository
import com.livetracker.controller.core.tracker.files.domain.TransferStatus
import com.livetracker.controller.core.tracker.signaling.AnswerPayload
import com.livetracker.controller.core.tracker.signaling.IceCandidatePayload
import com.livetracker.controller.core.tracker.signaling.OfferPayload
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFileTransferRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val deviceId = TrackerId("tracker-file-1")
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private var currentTime = 1000L

    private lateinit var authEngine: DefaultAuthorizationEngine
    private lateinit var fakeDataChannel: FakeTrackerDataChannel
    private lateinit var fakeSessionManager: FakeWebRtcSessionManager
    private lateinit var repository: DefaultFileTransferRepository

    private class FakeTrackerDataChannel(
        override val label: String = "file-transfer"
    ) : TrackerDataChannel {
        private val _state = MutableStateFlow(DataChannelState.OPEN)
        override val state: StateFlow<DataChannelState> = _state.asStateFlow()

        val sentMessages = mutableListOf<String>()
        private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()

        private val _incomingBinaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override val incomingBinaryMessages: Flow<ByteArray> = _incomingBinaryMessages.asSharedFlow()

        fun setClosed() {
            _state.value = DataChannelState.CLOSED
        }

        fun emitIncoming(msg: String) {
            _incomingMessages.tryEmit(msg)
        }

        fun emitBinary(bytes: ByteArray) {
            _incomingBinaryMessages.tryEmit(bytes)
        }

        override suspend fun open() {
            _state.value = DataChannelState.OPEN
        }

        override suspend fun send(data: String): Boolean {
            sentMessages.add(data)
            return true
        }

        override suspend fun close() {
            _state.value = DataChannelState.CLOSED
        }
    }

    private class FakeWebRtcSession(
        val channel: TrackerDataChannel
    ) : WebRtcSession {
        override val state: StateFlow<WebRtcState> = MutableStateFlow(WebRtcState.CONNECTED)
        override val localIceCandidates: Flow<IceCandidatePayload> = MutableSharedFlow()
        override val incomingDataChannels: Flow<TrackerDataChannel> = MutableSharedFlow()

        override fun getDataChannel(label: String): TrackerDataChannel? {
            return if (channel.label == label) channel else null
        }

        override fun createDataChannel(label: String): TrackerDataChannel? = channel
        override suspend fun setRemoteOfferAndCreateAnswer(offer: OfferPayload): AnswerPayload =
            AnswerPayload(sdp = "answer", generation = offer.generation)
        override suspend fun addIceCandidate(candidate: IceCandidatePayload) {}
        override suspend fun stop() {}
    }

    private class FakeWebRtcSessionManager(
        private val targetDeviceId: TrackerId,
        private val session: WebRtcSession?
    ) : WebRtcSessionManager {
        override fun createSession(
            trackerId: TrackerId,
            sessionId: String,
            configuration: WebRtcConfiguration
        ): WebRtcSession = session ?: throw IllegalStateException("No session")

        override fun getSession(trackerId: TrackerId): WebRtcSession? = session
        override fun removeSession(trackerId: TrackerId) {}
        override fun getAllSessions(): Map<TrackerId, WebRtcSession> =
            if (session != null) mapOf(targetDeviceId to session) else emptyMap()
    }

    private fun createValidSession(
        userId: String = "admin-1",
        role: Role = Role.ADMIN,
        permissions: Set<ServicePermission> = setOf(ServicePermission.FILE_READ, ServicePermission.FILE_TRANSFER)
    ): Session {
        return Session(
            sessionId = "session-1",
            user = User(
                id = userId,
                displayName = "Admin User",
                role = role,
                accountStatus = AccountStatus.ACTIVE,
                devicePermissions = listOf(
                    DevicePermission(
                        deviceId = deviceId.value,
                        allowedServices = permissions
                    )
                )
            ),
            isAuthenticated = true,
            expiresAt = currentTime + 3600_000L,
            createdAt = currentTime
        )
    }

    @Before
    fun setup() {
        authEngine = DefaultAuthorizationEngine()
        fakeDataChannel = FakeTrackerDataChannel()
        val fakeRtcSession = FakeWebRtcSession(fakeDataChannel)
        fakeSessionManager = FakeWebRtcSessionManager(deviceId, fakeRtcSession)

        repository = DefaultFileTransferRepository(
            webRtcSessionManager = fakeSessionManager,
            authEngine = authEngine,
            scope = testScope,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun listFiles_disconnectedChannel_failsClosedWithoutMocks() = runTest(testDispatcher) {
        val disconnectedManager = FakeWebRtcSessionManager(deviceId, null)
        val disconnectedRepo = DefaultFileTransferRepository(
            webRtcSessionManager = disconnectedManager,
            authEngine = authEngine,
            scope = testScope,
            timeProvider = { currentTime }
        )
        val session = createValidSession()

        val result = disconnectedRepo.listFiles(deviceId, "/sdcard", session)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        val state = disconnectedRepo.getFileTransferState(deviceId)
        assertEquals(true, state?.files?.isEmpty())
    }

    @Test
    fun listFiles_openChannel_sendsRequestAndReceivesFileList() = runTest(testDispatcher) {
        val session = createValidSession()

        testScope.launch {
            // Emulate Tracker response when request is sent
            while (fakeDataChannel.sentMessages.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
            val requestJson = fakeDataChannel.sentMessages.first()
            val transferId = kotlinx.serialization.json.Json.parseToJsonElement(requestJson)
                .let { (it as kotlinx.serialization.json.JsonObject)["transferId"]?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } }

            val response = """{
                "type": "fileList",
                "transferId": "$transferId",
                "path": "/sdcard",
                "files": [
                    {"id": "f1", "name": "log.txt", "path": "/sdcard/log.txt", "sizeBytes": 1024, "isDirectory": false, "readable": true, "writable": false}
                ],
                "success": true
            }"""
            fakeDataChannel.emitIncoming(response)
        }

        val result = repository.listFiles(deviceId, "/sdcard", session)
        assertTrue(result.isSuccess)
        val files = result.getOrNull()
        assertNotNull(files)
        assertEquals(1, files?.size)
        assertEquals("log.txt", files?.first()?.name)
        assertEquals(1024L, files?.first()?.sizeBytes)
    }

    @Test
    fun downloadFile_receivesChunksAndValidatesSha256() = runTest(testDispatcher) {
        val session = createValidSession()
        val targetFile = File(tempFolder.root, "downloaded_test.bin")
        val sourcePayload = "TRACE_REAL_VERIFIED_DEVICE_PAYLOAD_BYTES_2026".toByteArray(Charsets.UTF_8)
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(sourcePayload)
            .joinToString("") { "%02x".format(it) }

        testScope.launch {
            while (fakeDataChannel.sentMessages.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
            val requestJson = fakeDataChannel.sentMessages.first()
            val transferId = kotlinx.serialization.json.Json.parseToJsonElement(requestJson)
                .let { (it as kotlinx.serialization.json.JsonObject)["transferId"]?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } }!!

            // Frame binary chunk format: [1 (1B)][idLength (1B)][transferId][chunkIndex (8B)][payload]
            val idBytes = transferId.toByteArray(Charsets.UTF_8)
            val chunkBuffer = ByteBuffer.allocate(1 + 1 + idBytes.size + 8 + sourcePayload.size)
            chunkBuffer.put(1.toByte())
            chunkBuffer.put(idBytes.size.toByte())
            chunkBuffer.put(idBytes)
            chunkBuffer.putLong(0L)
            chunkBuffer.put(sourcePayload)
            val chunkBytes = chunkBuffer.array()

            fakeDataChannel.emitBinary(chunkBytes)

            // Complete message with sha256
            val completeMsg = """{
                "type": "complete",
                "transferId": "$transferId",
                "path": "/sdcard/test.bin",
                "bytesTransferred": ${sourcePayload.size},
                "finalSizeBytes": ${sourcePayload.size},
                "sha256": "$expectedSha256",
                "state": "COMPLETED"
            }"""
            fakeDataChannel.emitIncoming(completeMsg)
        }

        val downloadResult = repository.downloadFile(deviceId, "/sdcard/test.bin", targetFile, session)
        assertTrue(downloadResult.isSuccess)
        val transferId = downloadResult.getOrNull()!!

        // Await transfer completion
        repository.observeFileTransferState(deviceId).first { state ->
            state.activeTransfers.any { it.transferId == transferId && it.status == TransferStatus.COMPLETED }
        }

        // Verify target file exists and bytes match exactly
        assertTrue(targetFile.exists())
        assertEquals(sourcePayload.size.toLong(), targetFile.length())
        assertArrayEquals(sourcePayload, targetFile.readBytes())
    }

    @Test
    fun downloadFile_corruptedSha256_failsIntegrityCheck() = runTest(testDispatcher) {
        val session = createValidSession()
        val targetFile = File(tempFolder.root, "corrupted_download.bin")
        val sourcePayload = "AUTHENTIC_PAYLOAD".toByteArray(Charsets.UTF_8)
        val badSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

        testScope.launch {
            while (fakeDataChannel.sentMessages.isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
            val requestJson = fakeDataChannel.sentMessages.first()
            val transferId = kotlinx.serialization.json.Json.parseToJsonElement(requestJson)
                .let { (it as kotlinx.serialization.json.JsonObject)["transferId"]?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } }!!

            val idBytes = transferId.toByteArray(Charsets.UTF_8)
            val chunkBuffer = ByteBuffer.allocate(1 + 1 + idBytes.size + 8 + sourcePayload.size)
            chunkBuffer.put(1.toByte())
            chunkBuffer.put(idBytes.size.toByte())
            chunkBuffer.put(idBytes)
            chunkBuffer.putLong(0L)
            chunkBuffer.put(sourcePayload)

            fakeDataChannel.emitBinary(chunkBuffer.array())

            val completeMsg = """{
                "type": "complete",
                "transferId": "$transferId",
                "path": "/sdcard/corrupted.bin",
                "bytesTransferred": ${sourcePayload.size},
                "finalSizeBytes": ${sourcePayload.size},
                "sha256": "$badSha256",
                "state": "COMPLETED"
            }"""
            fakeDataChannel.emitIncoming(completeMsg)
        }

        val downloadResult = repository.downloadFile(deviceId, "/sdcard/corrupted.bin", targetFile, session)
        assertTrue(downloadResult.isSuccess) // Returns transferId as started

        val state = repository.getFileTransferState(deviceId)
        assertNotNull(state)
        val transfer = state?.activeTransfers?.firstOrNull()
        assertNotNull(transfer)
        // Wait or observe failure in state
        kotlinx.coroutines.delay(50)
        val updatedState = repository.getFileTransferState(deviceId)
        val updatedTransfer = updatedState?.activeTransfers?.firstOrNull()
        assertEquals(TransferStatus.ERROR, updatedTransfer?.status)
        assertTrue(updatedTransfer?.errorMessage?.contains("SHA-256 integrity check failed") == true)
    }

    @Test
    fun listFiles_commandAckCompletesDeferredImmediatelyWithoutSelectPolling() = runTest(testDispatcher) {
        val session = createValidSession()
        fakeDataChannel.setClosed()

        val fakeTrackerSessionManager = object : com.livetracker.controller.core.tracker.domain.TrackerSessionManager {
            override fun registerTracker(deviceId: TrackerId, displayName: String) {}
            override fun unregisterTracker(deviceId: TrackerId) {}
            override fun clearSessions() {}
            override fun getSession(deviceId: TrackerId): StateFlow<com.livetracker.controller.core.tracker.domain.TrackerSessionState>? = null
            override fun getAllSessions(): List<StateFlow<com.livetracker.controller.core.tracker.domain.TrackerSessionState>> = emptyList()
            override fun hasSession(deviceId: TrackerId): Boolean = true
            override fun listSessions(): List<TrackerId> = listOf(deviceId)
            override suspend fun startConnection(deviceId: TrackerId) {}
            override suspend fun disconnect(deviceId: TrackerId) {}
            override suspend fun sendCommand(command: com.livetracker.controller.core.tracker.domain.TrackerCommand): com.livetracker.controller.core.tracker.domain.CommandResult {
                return com.livetracker.controller.core.tracker.domain.CommandResult.Accepted(
                    commandId = command.commandId,
                    deviceId = command.deviceId
                )
            }
        }

        val repo = DefaultFileTransferRepository(
            webRtcSessionManager = fakeSessionManager,
            sessionManager = fakeTrackerSessionManager,
            authEngine = authEngine,
            scope = testScope,
            timeProvider = { currentTime }
        )

        var listResult: Result<List<com.livetracker.controller.core.tracker.files.domain.RemoteFileItem>>? = null
        val job = launch {
            listResult = repo.listFiles(deviceId, "/storage", session)
        }

        testScheduler.advanceTimeBy(10)
        val idKey = repo.pendingAcks.keys().toList().firstOrNull()
        assertNotNull("pendingAcks must contain the command idempotency key", idKey)

        val ack = com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope(
            idempotencyKey = idKey!!,
            status = "COMPLETED",
            resultPayload = """{"path":"/storage","files":[{"id":"f1","name":"document.pdf","path":"/storage/document.pdf","sizeBytes":1024,"modifiedTimeMillis":1700000000,"isDirectory":false}]}"""
        )
        repo.pendingAcks[idKey]?.complete(ack)
        testScheduler.advanceUntilIdle()

        job.join()
        assertNotNull(listResult)
        assertTrue(listResult!!.isSuccess)
        val files = listResult!!.getOrNull()
        assertNotNull(files)
        assertEquals(1, files!!.size)
        assertEquals("document.pdf", files[0].name)
        assertTrue("pendingAcks must be cleaned up", repo.pendingAcks.isEmpty())
    }
}
