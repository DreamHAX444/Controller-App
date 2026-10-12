package com.livetracker.controller.core.update.model

/**
 * High-level update availability and comparison summary evaluated against the local app version.
 */
data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val isMandatory: Boolean,
    val currentVersionCode: Long,
    val currentVersionName: String,
    val latestVersionCode: Long,
    val latestVersionName: String,
    val releaseNotes: String,
    val apkSizeBytes: Long,
    val publishedAt: String,
    val manifest: UpdateManifest?
)
