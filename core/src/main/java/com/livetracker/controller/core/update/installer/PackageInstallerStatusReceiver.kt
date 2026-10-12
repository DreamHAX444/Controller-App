package com.livetracker.controller.core.update.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * System BroadcastReceiver receiving status callbacks from Android's PackageInstaller.Session.
 */
class PackageInstallerStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Status: $status"

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                if (confirmationIntent != null) {
                    confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmationIntent)
                    PackageInstallerStatusBus.emitEvent(PackageInstallerEvent.PendingUserActionLaunched)
                } else {
                    PackageInstallerStatusBus.emitEvent(
                        PackageInstallerEvent.Failure(sessionId, status, "System required user action but provided no confirmation intent")
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                PackageInstallerStatusBus.emitEvent(PackageInstallerEvent.Success(sessionId))
            }

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val readableReason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation aborted by user"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by device policy"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package signature conflict or incompatible upgrade"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package incompatible with this device"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space for installation"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package format"
                    else -> message
                }
                PackageInstallerStatusBus.emitEvent(
                    PackageInstallerEvent.Failure(sessionId, status, readableReason)
                )
            }

            else -> {
                PackageInstallerStatusBus.emitEvent(
                    PackageInstallerEvent.Failure(sessionId, status, message)
                )
            }
        }
    }
}
