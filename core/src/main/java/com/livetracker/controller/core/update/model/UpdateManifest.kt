package com.livetracker.controller.core.update.model

import kotlinx.serialization.Serializable

/**
 * Versioned online update manifest schema.
 * Represents the authoritative release metadata published by the release pipeline.
 */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int = 1,
    val appId: String,
    val channel: String = "stable",
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long = 1L,
    val mandatory: Boolean = false,
    val publishedAt: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String? = null
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be positive" }
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(versionCode > 0) { "versionCode must be greater than 0" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(apkUrl.isNotBlank()) { "apkUrl must not be blank" }
        require(apkUrl.startsWith("https://") || apkUrl.startsWith("http://localhost") || apkUrl.startsWith("http://127.0.0.1")) {
            "apkUrl must use secure HTTPS protocol"
        }
        require(apkSizeBytes > 0) { "apkSizeBytes must be greater than 0" }
        require(sha256.length == 64 && sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            "sha256 must be a 64-character hexadecimal string"
        }
        if (signingCertSha256 != null) {
            require(signingCertSha256.length == 64 && signingCertSha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                "signingCertSha256 must be a 64-character hexadecimal string"
            }
        }
    }
}
