package com.livetracker.controller.core.tracker.files.domain

data class RemoteFileItem(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long = System.currentTimeMillis(),
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val readable: Boolean = true,
    val writable: Boolean = false
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "--"
            if (sizeBytes < 1024) return "$sizeBytes B"
            val exp = (Math.log(sizeBytes.toDouble()) / Math.log(1024.0)).toInt()
            val pre = "KMGTPE"[exp - 1]
            return String.format("%.1f %sB", sizeBytes / Math.pow(1024.0, exp.toDouble()), pre)
        }
}
