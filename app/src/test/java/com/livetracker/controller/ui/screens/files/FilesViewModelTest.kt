package com.livetracker.controller.ui.screens.files

import com.livetracker.controller.core.auth.data.StubSessionRepository
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.core.tracker.device.data.DefaultDeviceSelectionManager
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.files.domain.FileTransferRepository
import com.livetracker.controller.core.tracker.files.domain.FileTransferState
import com.livetracker.controller.core.tracker.files.domain.RemoteFileItem
import com.livetracker.controller.core.tracker.files.domain.TransferProgress
import com.livetracker.controller.core.tracker.files.domain.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fileRepo: FakeFileTransferRepository
    private lateinit var selectionManager: DefaultDeviceSelectionManager
    private lateinit var sessionRepo: StubSessionRepository
    private lateinit var viewModel: FilesViewModel

    private val testDeviceId = TrackerId("dev1")

    private class FakeFileTransferRepository(private val deviceId: TrackerId) : FileTransferRepository {
        val stateFlow = MutableStateFlow(
            FileTransferState(
                deviceId = deviceId,
                files = listOf(
                    RemoteFileItem("1", "doc1.pdf", "/sdcard/doc1.pdf", 1024L, isDirectory = false),
                    RemoteFileItem("2", "img1.png", "/sdcard/img1.png", 2048L, isDirectory = false),
                    RemoteFileItem("3", "FolderA", "/sdcard/FolderA", 0L, isDirectory = true),
                    RemoteFileItem("4", "app.apk", "/sdcard/app.apk", 4096L, isDirectory = false)
                )
            )
        )
        val downloadedPaths = mutableListOf<String>()

        override fun observeFileTransferState(deviceId: TrackerId): Flow<FileTransferState> = stateFlow.asStateFlow()

        override suspend fun getFileTransferState(deviceId: TrackerId): FileTransferState? = stateFlow.value

        override suspend fun listFiles(deviceId: TrackerId, path: String, session: Session?): Result<List<RemoteFileItem>> =
            Result.success(stateFlow.value.files)

        override suspend fun downloadFile(deviceId: TrackerId, remotePath: String, targetFile: File, session: Session?): Result<String> {
            val transferId = UUID.randomUUID().toString()
            downloadedPaths.add(remotePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes("DUMMY_PAYLOAD_FOR_TEST".toByteArray())

            val item = stateFlow.value.files.find { it.path == remotePath }
            val progress = TransferProgress(
                transferId = transferId,
                fileName = item?.name ?: remotePath.substringAfterLast('/'),
                remotePath = remotePath,
                bytesTransferred = item?.sizeBytes ?: 22L,
                totalBytes = item?.sizeBytes ?: 22L,
                status = TransferStatus.COMPLETED
            )
            stateFlow.update { it.copy(activeTransfers = it.activeTransfers + progress) }
            return Result.success(transferId)
        }

        override suspend fun uploadFile(deviceId: TrackerId, targetDirectory: String, sourceFile: File, session: Session?): Result<String> =
            Result.success(UUID.randomUUID().toString())

        override suspend fun deleteFile(deviceId: TrackerId, remotePath: String, session: Session?): Result<Unit> =
            Result.success(Unit)

        override suspend fun cancelTransfer(deviceId: TrackerId, transferId: String): Result<Unit> =
            Result.success(Unit)

        override fun clearCompletedTransfers(deviceId: TrackerId) {
            stateFlow.update { it.copy(activeTransfers = emptyList()) }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fileRepo = FakeFileTransferRepository(testDeviceId)
        selectionManager = DefaultDeviceSelectionManager()
        sessionRepo = StubSessionRepository()

        val activeSession = Session(
            sessionId = "sess_1",
            user = User("u0", "owner", Role.OWNER, AccountStatus.ACTIVE, emptyList()),
            isAuthenticated = true,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 3600_000L
        )
        kotlinx.coroutines.runBlocking {
            sessionRepo.createSession(activeSession)
        }

        viewModel = FilesViewModel(
            fileTransferRepository = fileRepo,
            deviceSelectionManager = selectionManager,
            sessionRepository = sessionRepo,
            ioDispatcher = testDispatcher
        )

        selectionManager.selectDevice(testDeviceId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleSelectionMode_enablesAndClearsOnExit() = runTest(testDispatcher) {
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedFilePaths.isEmpty())

        viewModel.toggleSelectionMode(true)
        assertTrue(viewModel.uiState.value.isSelectionMode)

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        assertEquals(1, viewModel.uiState.value.selectedFilePaths.size)
        assertTrue(viewModel.uiState.value.selectedFilePaths.contains("/sdcard/doc1.pdf"))

        // Exiting selection mode clears selections
        viewModel.toggleSelectionMode(false)
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedFilePaths.isEmpty())
    }

    @Test
    fun selectAll_selectsAllFilesAndDirectories() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.selectAll()
        assertTrue(viewModel.uiState.value.isSelectionMode)
        val selected = viewModel.uiState.value.selectedFilePaths

        // Should contain doc1.pdf, img1.png, app.apk, AND FolderA
        assertEquals(4, selected.size)
        assertTrue(selected.contains("/sdcard/doc1.pdf"))
        assertTrue(selected.contains("/sdcard/img1.png"))
        assertTrue(selected.contains("/sdcard/app.apk"))
        assertTrue(selected.contains("/sdcard/FolderA"))
    }

    @Test
    fun downloadSelectedFiles_launchesConcurrentDownloadsAndCompletesSuccessfully() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")
        assertEquals(2, viewModel.uiState.value.selectedFilePaths.size)

        val destDir = tempFolder.newFolder("downloads")
        viewModel.downloadSelectedFiles(destDir)

        // Verifying batch state initialized
        val batchInit = viewModel.uiState.value.batchDownloadState
        assertTrue(batchInit.isActive || batchInit.isCompleted)
        assertEquals(2, batchInit.totalCount)
        assertTrue(viewModel.uiState.value.showBatchProgressDialog)

        // Await batch download completion
        val finalState = kotlinx.coroutines.withTimeout(5000L) {
            viewModel.uiState.first { it.batchDownloadState.isCompleted }
        }
        val batchFinal = finalState.batchDownloadState

        assertFalse(batchFinal.isActive)
        assertTrue(batchFinal.isCompleted)
        assertFalse(batchFinal.hasErrors)
        assertEquals(2, batchFinal.completedCount)
        assertEquals(1.0f, batchFinal.overallProgressFraction, 0.01f)

        // Verifying both files downloaded to disk
        assertTrue(File(destDir, "doc1.pdf").exists())
        assertTrue(File(destDir, "img1.png").exists())
        assertEquals(2, fileRepo.downloadedPaths.size)
    }

    @Test
    fun cancelBatchDownload_marksCancelledState() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.selectAll()
        val destDir = tempFolder.newFolder("downloads_cancel")
        viewModel.downloadSelectedFiles(destDir)

        viewModel.cancelBatchDownload()
        advanceUntilIdle()

        val batchState = viewModel.uiState.value.batchDownloadState
        assertFalse(batchState.isActive)
        assertTrue(batchState.isCompleted)
    }

    @Test
    fun requestDownloadFile_whenFileExists_triggersConflictPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        val destDir = tempFolder.newFolder("downloads_conflict_single")
        val existingFile = File(destDir, "doc1.pdf")
        existingFile.writeBytes("OLD_EXISTING_CONTENT".toByteArray())

        val item = RemoteFileItem("1", "doc1.pdf", "/sdcard/doc1.pdf", 1024L, isDirectory = false)
        viewModel.requestDownloadFile(item, destDir)
        advanceUntilIdle()

        val prompt = viewModel.uiState.value.fileConflictPrompt
        assertNotNull(prompt)
        assertTrue(prompt is FileConflictPrompt.Single)
        val single = prompt as FileConflictPrompt.Single
        assertEquals("doc1.pdf", single.item.name)
        assertEquals(existingFile.length(), single.existingSizeBytes)
        // No download initiated yet
        assertTrue(fileRepo.downloadedPaths.isEmpty())
    }

    @Test
    fun confirmReplaceSingleFile_downloadsAndClearsPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        val destDir = tempFolder.newFolder("downloads_replace_single")
        val existingFile = File(destDir, "doc1.pdf")
        existingFile.writeBytes("OLD_EXISTING_CONTENT".toByteArray())

        val item = RemoteFileItem("1", "doc1.pdf", "/sdcard/doc1.pdf", 1024L, isDirectory = false)
        viewModel.requestDownloadFile(item, destDir)
        assertNotNull(viewModel.uiState.value.fileConflictPrompt)

        viewModel.confirmReplaceSingleFile(item, destDir)
        assertNull(viewModel.uiState.value.fileConflictPrompt)

        advanceUntilIdle()
        assertTrue(fileRepo.downloadedPaths.contains("/sdcard/doc1.pdf"))
    }

    @Test
    fun dismissFileConflictPrompt_clearsPromptWithoutDownloading() = runTest(testDispatcher) {
        advanceUntilIdle()

        val destDir = tempFolder.newFolder("downloads_dismiss_single")
        File(destDir, "doc1.pdf").writeBytes("OLD".toByteArray())

        val item = RemoteFileItem("1", "doc1.pdf", "/sdcard/doc1.pdf", 1024L, isDirectory = false)
        viewModel.requestDownloadFile(item, destDir)
        assertNotNull(viewModel.uiState.value.fileConflictPrompt)

        viewModel.dismissFileConflictPrompt()
        assertNull(viewModel.uiState.value.fileConflictPrompt)
        assertTrue(fileRepo.downloadedPaths.isEmpty())
    }

    @Test
    fun requestDownloadSelectedFiles_whenConflictsExist_triggersBatchConflictPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        val destDir = tempFolder.newFolder("downloads_conflict_batch")
        // Pre-create doc1.pdf to produce a conflict
        File(destDir, "doc1.pdf").writeBytes("EXISTING".toByteArray())

        viewModel.requestDownloadSelectedFiles(destDir)
        advanceUntilIdle()

        val prompt = viewModel.uiState.value.fileConflictPrompt
        assertNotNull(prompt)
        assertTrue(prompt is FileConflictPrompt.Batch)
        val batch = prompt as FileConflictPrompt.Batch
        assertEquals(1, batch.conflictingFiles.size)
        assertEquals("doc1.pdf", batch.conflictingFiles.first().name)
        assertEquals(2, batch.allSelectedFiles.size)
        // No downloads started yet
        assertTrue(fileRepo.downloadedPaths.isEmpty())
    }

    @Test
    fun confirmReplaceAllBatch_downloadsAllFilesAndClearsPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        val destDir = tempFolder.newFolder("downloads_batch_replace_all")
        File(destDir, "doc1.pdf").writeBytes("EXISTING".toByteArray())

        viewModel.requestDownloadSelectedFiles(destDir)
        val prompt = viewModel.uiState.value.fileConflictPrompt as FileConflictPrompt.Batch

        viewModel.confirmReplaceAllBatch(prompt.allSelectedFiles, destDir)
        assertNull(viewModel.uiState.value.fileConflictPrompt)

        val finalState = kotlinx.coroutines.withTimeout(5000L) {
            viewModel.uiState.first { it.batchDownloadState.isCompleted }
        }
        assertEquals(2, finalState.batchDownloadState.completedCount)
        assertTrue(File(destDir, "doc1.pdf").exists())
        assertTrue(File(destDir, "img1.png").exists())
    }

    @Test
    fun confirmSkipExistingBatch_downloadsOnlyNonConflicting() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        val destDir = tempFolder.newFolder("downloads_batch_skip")
        val existingDoc = File(destDir, "doc1.pdf")
        existingDoc.writeBytes("EXISTING_NOT_OVERWRITTEN".toByteArray())

        viewModel.requestDownloadSelectedFiles(destDir)
        val prompt = viewModel.uiState.value.fileConflictPrompt as FileConflictPrompt.Batch
        val nonConflicting = prompt.allSelectedFiles.filter { it.name != "doc1.pdf" }

        viewModel.confirmSkipExistingBatch(nonConflicting, destDir)
        assertNull(viewModel.uiState.value.fileConflictPrompt)

        val finalState = kotlinx.coroutines.withTimeout(5000L) {
            viewModel.uiState.first { it.batchDownloadState.isCompleted }
        }
        assertEquals(1, finalState.batchDownloadState.completedCount)
        // doc1.pdf was skipped so remote repo never downloaded /sdcard/doc1.pdf
        assertFalse(fileRepo.downloadedPaths.contains("/sdcard/doc1.pdf"))
        assertTrue(fileRepo.downloadedPaths.contains("/sdcard/img1.png"))
        // Existing content preserved
        assertEquals("EXISTING_NOT_OVERWRITTEN", existingDoc.readText())
    }

    @Test
    fun confirmSkipExistingBatch_whenAllConflict_skipsAndReportsMessage() = runTest(testDispatcher) {
        advanceUntilIdle()

        val destDir = tempFolder.newFolder("downloads_batch_all_conflict")
        File(destDir, "doc1.pdf").writeBytes("EXISTING".toByteArray())

        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.requestDownloadSelectedFiles(destDir)
        assertTrue(viewModel.uiState.value.fileConflictPrompt is FileConflictPrompt.Batch)

        // Skip existing with empty list (all conflicted)
        viewModel.confirmSkipExistingBatch(emptyList(), destDir)
        assertNull(viewModel.uiState.value.fileConflictPrompt)
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedFilePaths.isEmpty())
        assertEquals("All selected files already exist. Skipped.", viewModel.uiState.value.successMessage)
        assertTrue(fileRepo.downloadedPaths.isEmpty())
    }

    @Test
    fun getEffectiveDownloadDestination_resolvesCorrectDirectory() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setDownloadLocation(DownloadLocationType.PUBLIC_DOWNLOADS)
        val pubDest = viewModel.getEffectiveDownloadDestination()
        assertTrue(pubDest.absolutePath.contains("Download"))

        viewModel.setDownloadLocation(DownloadLocationType.APP_STORAGE)
        val appDest = viewModel.getEffectiveDownloadDestination()
        assertTrue(appDest.absolutePath.contains("com.livetracker.controller"))
    }

    @Test
    fun requestDownloadSelectedFiles_whenAlwaysAsk_triggersBatchLocationPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setDownloadLocation(DownloadLocationType.ALWAYS_ASK)
        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        viewModel.requestDownloadSelectedFiles()
        advanceUntilIdle()

        val prompt = viewModel.uiState.value.downloadLocationPrompt
        assertNotNull(prompt)
        assertTrue(prompt is DownloadLocationPrompt.Batch)
        val batch = prompt as DownloadLocationPrompt.Batch
        assertEquals(2, batch.items.size)
        assertTrue(fileRepo.downloadedPaths.isEmpty())
    }

    @Test
    fun confirmDownloadBatchDefault_downloadsAllFilesAndDismissesPrompt() = runTest(testDispatcher) {
        advanceUntilIdle()

        val destDir = viewModel.getEffectiveDownloadDestination()
        File(destDir, "doc1.pdf").delete()
        File(destDir, "img1.png").delete()

        viewModel.setDownloadLocation(DownloadLocationType.ALWAYS_ASK)
        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        viewModel.requestDownloadSelectedFiles()
        val prompt = viewModel.uiState.value.downloadLocationPrompt as DownloadLocationPrompt.Batch

        viewModel.confirmDownloadBatchDefault(prompt.items)
        assertNull(viewModel.uiState.value.downloadLocationPrompt)

        val finalState = kotlinx.coroutines.withTimeout(5000L) {
            viewModel.uiState.first { it.batchDownloadState.isCompleted }
        }
        assertEquals(2, finalState.batchDownloadState.completedCount)
        assertTrue(fileRepo.downloadedPaths.contains("/sdcard/doc1.pdf"))
        assertTrue(fileRepo.downloadedPaths.contains("/sdcard/img1.png"))
    }

    @Test
    fun confirmDownloadBatchCustom_downloadsAllFilesToCustomDir() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.setDownloadLocation(DownloadLocationType.ALWAYS_ASK)
        viewModel.toggleFileSelection("/sdcard/doc1.pdf")
        viewModel.toggleFileSelection("/sdcard/img1.png")

        viewModel.requestDownloadSelectedFiles()
        val prompt = viewModel.uiState.value.downloadLocationPrompt as DownloadLocationPrompt.Batch

        val customDir = tempFolder.newFolder("custom_dest_folder")
        viewModel.confirmDownloadBatchCustom(prompt.items, customDir)
        assertNull(viewModel.uiState.value.downloadLocationPrompt)

        val finalState = kotlinx.coroutines.withTimeout(5000L) {
            viewModel.uiState.first { it.batchDownloadState.isCompleted }
        }
        assertEquals(2, finalState.batchDownloadState.completedCount)
        assertTrue(File(customDir, "doc1.pdf").exists())
        assertTrue(File(customDir, "img1.png").exists())
    }
}
