package com.livetracker.controller.ui.screens.updates

import com.livetracker.controller.core.update.domain.UpdateRepository
import com.livetracker.controller.core.update.domain.usecase.CheckForUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.DownloadUpdateUseCase
import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.model.UpdateInfo
import com.livetracker.controller.core.update.model.UpdateManifest
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.navigation.Destination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpenUpdateFlowTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeUpdateRepository
    private lateinit var checkForUpdateUseCase: CheckForUpdateUseCase
    private lateinit var downloadUpdateUseCase: DownloadUpdateUseCase

    @Before
    fun setUp() {
        fakeRepository = FakeUpdateRepository()
        checkForUpdateUseCase = CheckForUpdateUseCase(fakeRepository)
        downloadUpdateUseCase = DownloadUpdateUseCase(fakeRepository)
    }

    @Test
    fun appOpen_triggersForcedUpdateCheck() = runTest(testDispatcher) {
        // When app opens, checkForUpdateUseCase(force = true) is called
        checkForUpdateUseCase(force = true)
        advanceUntilIdle()

        assertTrue("Check for updates must be called on app open", fakeRepository.checkForUpdatesCalled)
        assertEquals("Check for updates on app open must use force=true", true, fakeRepository.lastForceParam)
    }

    @Test
    fun appOpen_whenUpToDate_informsUserUpToDate() = runTest(testDispatcher) {
        fakeRepository.nextUpdateInfo = null // Up to date
        fakeRepository.simulateCheckResult = UpdateState.UpToDate(
            lastCheckedEpochMs = 1000L,
            currentVersionCode = 1L,
            currentVersionName = "1.0.0"
        )

        checkForUpdateUseCase(force = true)
        advanceUntilIdle()

        val state = fakeRepository.updateState.value
        assertTrue("State must be UpToDate", state is UpdateState.UpToDate)
        val upToDate = state as UpdateState.UpToDate
        assertEquals("1.0.0", upToDate.currentVersionName)
    }

    @Test
    fun appOpen_whenUpdateAvailable_informsUserUpdateAvailable() = runTest(testDispatcher) {
        val sampleInfo = sampleUpdateInfo(latestVersionCode = 2L, latestVersionName = "1.1.0")
        fakeRepository.simulateCheckResult = UpdateState.UpdateAvailable(sampleInfo)

        checkForUpdateUseCase(force = true)
        advanceUntilIdle()

        val state = fakeRepository.updateState.value
        assertTrue("State must be UpdateAvailable", state is UpdateState.UpdateAvailable)
        val available = state as UpdateState.UpdateAvailable
        assertEquals(2L, available.updateInfo.latestVersionCode)
        assertEquals("1.1.0", available.updateInfo.latestVersionName)
    }

    @Test
    fun appOpen_whenUserWantsToUpdate_takesUserToUpdateSectionAndStartsDownload() = runTest(testDispatcher) {
        var navigatedDestination: String? = null
        var sessionPromptDismissed = false

        val sampleInfo = sampleUpdateInfo(latestVersionCode = 2L, latestVersionName = "1.1.0")
        fakeRepository.simulateCheckResult = UpdateState.UpdateAvailable(sampleInfo)
        checkForUpdateUseCase(force = true)
        advanceUntilIdle()

        // User taps "Update Now"
        val onUpdateNow = {
            sessionPromptDismissed = true
            this@runTest.launch { downloadUpdateUseCase() }
            navigatedDestination = Destination.Updates.route
        }

        onUpdateNow()
        advanceUntilIdle()

        assertEquals(Destination.Updates.route, navigatedDestination)
        assertTrue("Download should start when user wants to update", fakeRepository.startDownloadCalled)
        assertTrue("Modal dialog prompt should be dismissed once navigation begins", sessionPromptDismissed)
    }

    @Test
    fun appOpen_whenUserDeclines_persistsUpdateAndRepromptsOnNextAppOpen() = runTest(testDispatcher) {
        var isBackgrounded = false
        var sessionPromptDismissed = false

        val sampleInfo = sampleUpdateInfo(latestVersionCode = 2L, latestVersionName = "1.1.0")
        fakeRepository.simulateCheckResult = UpdateState.UpdateAvailable(sampleInfo)

        // 1. Initial app open: prompt shown
        checkForUpdateUseCase(force = true)
        advanceUntilIdle()
        assertTrue(fakeRepository.updateState.value is UpdateState.UpdateAvailable)

        // User taps "Later"
        sessionPromptDismissed = true

        // For this session: modal is dismissed, but updateState is NOT destroyed
        assertTrue("Modal prompt is dismissed for current foreground session", sessionPromptDismissed)
        assertTrue("Update remains active so top banner keeps informing the user", fakeRepository.updateState.value is UpdateState.UpdateAvailable)

        // 2. User backgrounds the app (onStop)
        isBackgrounded = true

        // 3. User re-opens the app (onStart)
        if (isBackgrounded) {
            sessionPromptDismissed = false
            isBackgrounded = false
        }
        checkForUpdateUseCase(force = true)
        advanceUntilIdle()

        // On re-opening, the user is prompted again
        assertFalse("Prompt must NOT be suppressed on subsequent app open", sessionPromptDismissed)
        assertTrue("App must inform user again that app is not up to date", fakeRepository.updateState.value is UpdateState.UpdateAvailable)
    }

    private fun sampleUpdateInfo(latestVersionCode: Long, latestVersionName: String): UpdateInfo {
        return UpdateInfo(
            isUpdateAvailable = true,
            isMandatory = false,
            currentVersionCode = 1L,
            currentVersionName = "1.0.0",
            latestVersionCode = latestVersionCode,
            latestVersionName = latestVersionName,
            releaseNotes = "New features",
            apkSizeBytes = 10_000_000L,
            publishedAt = "2026-10-12T00:00:00Z",
            manifest = UpdateManifest(
                schemaVersion = 1,
                appId = "com.livetracker.controller",
                channel = "stable",
                versionCode = latestVersionCode,
                versionName = latestVersionName,
                publishedAt = "2026-10-12T00:00:00Z",
                releaseNotes = "New features",
                apkUrl = "https://example.com/app.apk",
                apkSizeBytes = 10_000_000L,
                sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
            )
        )
    }

    private class FakeUpdateRepository : UpdateRepository {
        override val appId: String = "com.livetracker.controller"
        override val currentVersionCode: Long = 1L
        override val currentVersionName: String = "1.0.0"

        private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

        var checkForUpdatesCalled = false
        var lastForceParam: Boolean? = null
        var startDownloadCalled = false
        var nextUpdateInfo: UpdateInfo? = null
        var simulateCheckResult: UpdateState? = null

        override suspend fun checkForUpdates(force: Boolean): Result<UpdateInfo> {
            checkForUpdatesCalled = true
            lastForceParam = force
            val resultState = simulateCheckResult ?: UpdateState.UpToDate(
                lastCheckedEpochMs = 1234L,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName
            )
            _updateState.value = resultState
            val info = nextUpdateInfo ?: UpdateInfo(
                isUpdateAvailable = false,
                isMandatory = false,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName,
                latestVersionCode = currentVersionCode,
                latestVersionName = currentVersionName,
                releaseNotes = "",
                apkSizeBytes = 1024L,
                publishedAt = "",
                manifest = UpdateManifest(
                    schemaVersion = 1,
                    appId = appId,
                    channel = "stable",
                    versionCode = currentVersionCode,
                    versionName = currentVersionName,
                    publishedAt = "",
                    releaseNotes = "",
                    apkUrl = "https://example.com/app.apk",
                    apkSizeBytes = 1024L,
                    sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
                )
            )
            return Result.success(info)
        }

        override suspend fun startDownload(): Result<Unit> {
            startDownloadCalled = true
            return Result.success(Unit)
        }

        override fun cancelDownload() {}
        override suspend fun installDownloadedUpdate(): Result<InstallationRequestResult> = Result.success(InstallationRequestResult.CompletedImmediately)
        override suspend fun retry() {}
        override suspend fun dismissUpdate(versionCode: Long) {}
        override fun resetState() { _updateState.value = UpdateState.Idle }
    }
}
