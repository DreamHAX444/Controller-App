package com.livetracker.controller.core.update.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

sealed interface InstallationRequestResult {
    object CompletedImmediately : InstallationRequestResult
    object SystemConfirmationLaunched : InstallationRequestResult
    data class RequiresSettingsPermission(val settingsIntent: Intent? = null) : InstallationRequestResult
}

/**
 * Interface abstraction for package installation operations.
 */
interface UpdateInstaller {
    fun canRequestPackageInstalls(): Boolean
    fun getManageUnknownAppSourcesIntent(): Intent?
    suspend fun installApk(apkFile: File): Result<InstallationRequestResult>
}

/**
 * Production implementation of [UpdateInstaller] for Android 8.0+ (API 26 to 35).
 * Supports native PackageInstaller.Session streaming with automatic user confirmation handling,
 * and resilient FileProvider fallback.
 */
class AndroidPackageInstaller(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UpdateInstaller {

    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun getManageUnknownAppSourcesIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    override suspend fun installApk(apkFile: File): Result<InstallationRequestResult> = withContext(ioDispatcher) {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return@withContext Result.failure(IOException("APK file not found or not readable: ${apkFile.absolutePath}"))
        }

        // Check permission on Android 8.0+
        if (!canRequestPackageInstalls()) {
            val settingsIntent = getManageUnknownAppSourcesIntent()
                ?: Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return@withContext Result.success(InstallationRequestResult.RequiresSettingsPermission(settingsIntent))
        }

        try {
            // Strategy 1: Native PackageInstaller.Session
            installViaPackageInstallerSession(apkFile)
            Result.success(InstallationRequestResult.SystemConfirmationLaunched)
        } catch (e: Throwable) {
            // Strategy 2: Resilient FileProvider Intent fallback if PackageInstaller session fails
            try {
                installViaFileProviderIntent(apkFile)
                Result.success(InstallationRequestResult.SystemConfirmationLaunched)
            } catch (fallbackError: Throwable) {
                Result.failure(IOException("Both PackageInstaller session and FileProvider fallback failed: ${e.message} / ${fallbackError.message}", e))
            }
        }
    }

    private fun installViaPackageInstallerSession(apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { activeSession ->
            FileInputStream(apkFile).use { inputStream ->
                activeSession.openWrite("package", 0, apkFile.length()).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    activeSession.fsync(outputStream)
                }
            }

            val callbackIntent = Intent(context, PackageInstallerStatusReceiver::class.java).apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )

            activeSession.commit(pendingIntent.intentSender)
        }
    }

    private fun installViaFileProviderIntent(apkFile: File) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
