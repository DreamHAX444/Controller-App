package com.livetracker.controller.ui.screens.updates

import android.content.Intent
import com.livetracker.controller.core.update.domain.UpdateRepository
import com.livetracker.controller.core.update.domain.usecase.CheckForUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.DismissUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.DownloadUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.InstallUpdateUseCase
import com.livetracker.controller.core.update.domain.usecase.ObserveUpdateStateUseCase
import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.installer.UpdateInstaller
import com.livetracker.controller.core.update.model.UpdateInfo
import com.livetracker.controller.core.update.model.UpdateManifest
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.core.update.storage.InMemoryUpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeRepository: FakeUpdateRepository
    private lateinit var fakeInstaller: FakeInstaller
    private lateinit var preferences: InMemoryUpdatePreferencesRepository

    private lateinit var checkForUpdateUseCase: CheckForUpdateUseCase
    private lateinit var downloadUpdateUseCase: DownloadUpdateUseCase
    private lateinit var installUpdateUseCase: InstallUpdateUseCase
    private lateinit var dismissUpdateUseCase: DismissUpdateUseCase
    private lateinit var observeUpdateStateUseCase: ObserveUpdateStateUseCase

    private lateinit var viewModel: UpdatesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakeRepository = FakeUpdateRepository()
        fakeInstaller = FakeInstaller()
        preferences = InMemoryUpdatePreferencesRepository(
            initialLastCheckedMs = 123456789L,
            initialChannel = "stable"
        )

        checkForUpdateUseCase = CheckForUpdateUseCase(fakeRepository)
        downloadUpdateUseCase = DownloadUpdateUseCase(fakeRepository)
        installUpdateUseCase = InstallUpdateUseCase(fakeRepository)
        dismissUpdateUseCase = DismissUpdateUseCase(fakeRepository)
        observeUpdateStateUseCase = ObserveUpdateStateUseCase(fakeRepository)

        viewModel = UpdatesViewModel(
            currentVersionCode = 1L,
            currentVersionName = "1.0.0",
            checkForUpdateUseCase = checkForUpdateUseCase,
            downloadUpdateUseCase = downloadUpdateUseCase,
            installUpdateUseCase = installUpdateUseCase,
            dismissUpdateUseCase = dismissUpdateUseCase,
            observeUpdateStateUseCase = observeUpdateStateUseCase,
            preferencesRepository = preferences,
            updateInstaller = fakeInstaller
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_reflectsDefaultAndPreferencesValues() = runTest(testDispatcher) {
        val collectJob = launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1L, state.currentVersionCode)
        assertEquals("1.0.0", state.currentVersionName)
        assertEquals(UpdateState.Idle, state.updateState)
        assertEquals(123456789L, state.lastCheckedEpochMs)
        assertEquals("stable", state.channel)
        assertTrue(state.canRequestPackageInstalls)

        collectJob.cancel()
    }

    @Test
    fun checkForUpdates_invokesUseCaseWithForceFlag() = runTest(testDispatcher) {
        viewModel.checkForUpdates(force = true)
        advanceUntilIdle()

        assertTrue(fakeRepository.checkForUpdatesCalled)
        assertEquals(true, fakeRepository.lastForceParam)
    }

    @Test
    fun startDownload_invokesDownloadUseCase() = runTest(testDispatcher) {
        viewModel.startDownload()
        advanceUntilIdle()

        assertTrue(fakeRepository.startDownloadCalled)
    }

    @Test
    fun cancelDownload_invokesCancel() = runTest(testDispatcher) {
        viewModel.cancelDownload()
        advanceUntilIdle()

        assertTrue(fakeRepository.cancelDownloadCalled)
    }

    @Test
    fun installUpdate_permissionDenied_emitsLaunchSettingsEffect() = runTest(testDispatcher) {
        fakeInstaller.canInstall = false
        val settingsIntent = Intent()
        fakeInstaller.settingsIntent = settingsIntent

        val effects = mutableListOf<UpdatesUiEffect>()
        val effectsJob = launch { viewModel.effects.collect { effects.add(it) } }

        viewModel.installUpdate()
        advanceUntilIdle()

        assertFalse(fakeRepository.installDownloadedUpdateCalled)
        assertEquals(1, effects.size)
        assertTrue(effects.first() is UpdatesUiEffect.LaunchSettings)

        effectsJob.cancel()
    }

    @Test
    fun installUpdate_permissionGranted_invokesInstallUseCase() = runTest(testDispatcher) {
        fakeInstaller.canInstall = true

        viewModel.installUpdate()
        advanceUntilIdle()

        assertTrue(fakeRepository.installDownloadedUpdateCalled)
    }

    @Test
    fun dismissUpdate_invokesDismissUseCase() = runTest(testDispatcher) {
        viewModel.dismissUpdate(42L)
        advanceUntilIdle()

        assertEquals(42L, fakeRepository.dismissedVersionCode)
    }

    @Test
    fun openSettingsForPermission_emitsLaunchSettingsEffect() = runTest(testDispatcher) {
        val settingsIntent = Intent()
        fakeInstaller.settingsIntent = settingsIntent

        val effects = mutableListOf<UpdatesUiEffect>()
        val effectsJob = launch { viewModel.effects.collect { effects.add(it) } }

        viewModel.openSettingsForPermission()
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects.first() is UpdatesUiEffect.LaunchSettings)

        effectsJob.cancel()
    }

    // --- Fakes ---

    private class FakeUpdateRepository : UpdateRepository {
        override val appId: String = "com.livetracker.controller"
        override val currentVersionCode: Long = 1L
        override val currentVersionName: String = "1.0.0"

        private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
        override val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

        var checkForUpdatesCalled = false
        var lastForceParam: Boolean? = null
        var startDownloadCalled = false
        var cancelDownloadCalled = false
        var installDownloadedUpdateCalled = false
        var dismissedVersionCode: Long? = null

        override suspend fun checkForUpdates(force: Boolean): Result<UpdateInfo> {
            checkForUpdatesCalled = true
            lastForceParam = force
            return Result.success(
                UpdateInfo(
                    isUpdateAvailable = false,
                    isMandatory = false,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName,
                    latestVersionCode = 1L,
                    latestVersionName = "1.0.0",
                    releaseNotes = "",
                    apkSizeBytes = 0L,
                    publishedAt = "",
                    manifest = UpdateManifest(
                        schemaVersion = 1,
                        appId = appId,
                        channel = "stable",
                        versionCode = 1L,
                        versionName = "1.0.0",
                        publishedAt = "",
                        releaseNotes = "",
                        apkUrl = "https://example.com/apk",
                        apkSizeBytes = 100,
                        sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
                    )
                )
            )
        }

        override suspend fun startDownload(): Result<Unit> {
            startDownloadCalled = true
            return Result.success(Unit)
        }

        override fun cancelDownload() {
            cancelDownloadCalled = true
        }

        override suspend fun installDownloadedUpdate(): Result<InstallationRequestResult> {
            installDownloadedUpdateCalled = true
            return Result.success(InstallationRequestResult.CompletedImmediately)
        }

        override suspend fun retry() {}

        override suspend fun dismissUpdate(versionCode: Long) {
            dismissedVersionCode = versionCode
        }

        override fun resetState() {
            _updateState.value = UpdateState.Idle
        }
    }

    private class FakeInstaller : UpdateInstaller {
        var canInstall: Boolean = true
        var settingsIntent: Intent? = null

        override fun canRequestPackageInstalls(): Boolean = canInstall

        override fun getManageUnknownAppSourcesIntent(): Intent? = settingsIntent

        override suspend fun installApk(apkFile: File): Result<InstallationRequestResult> {
            return Result.success(InstallationRequestResult.CompletedImmediately)
        }
    }
}
