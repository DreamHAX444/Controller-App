package com.livetracker.controller.ui.util

/**
 * Universal device display name formatter.
 * Ensures compact, friendly, and clean presentation across all screens.
 * Replaces ugly raw UUIDs or oversized emulator/manufacturer identifiers with concise labels.
 */
object DeviceNameFormatter {

    private val PARENTHETICAL_TAG_REGEX = Regex("\\s*\\([a-zA-Z0-9_-]+\\)\\s*")
    private val SAMSUNG_SHORT_MODEL_REGEX = Regex("(?i)Samsung\\s+SM-([A-Z0-9]+)")
    private val TAG_EXTRACTION_REGEX = Regex("\\(([a-zA-Z0-9_-]{3,8})\\)")
    private val TAG_SUFFIX_EXTRACTION_REGEX = Regex("\\(([a-zA-Z0-9_-]+)\\)")
    private val SAMSUNG_FULL_MODEL_REGEX = Regex("(?i)Samsung\\s+(?:SM-)?([A-Z])(\\d{1,2})\\d*([A-Z0-9]*)")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Formats a device's display name and deviceId into a concise, readable string.
     * E.g.
     * - "Google sdk_gphone16k_x86_64 (A7A0)" -> "Pixel (A7A0)"
     * - "Google sdk_gphone_x86" -> "Pixel Emulator"
     * - "Samsung SM-A156M (497E)" -> "Samsung A15 (497E)"
     * - "TECNO TECNO KL4 (86D6)" -> "Tecno KL4 (86D6)"
     * - "c3d4e5f6-7890-4123-abcd-ef0123456789" -> "C3D4E5F6"
     * - If custom friendly name (e.g. "Alpha Patrol"), returns it.
     */
    fun formatFriendlyName(
        displayName: String?,
        deviceId: String? = null,
        maxLength: Int = 22
    ): String {
        val raw = displayName?.trim().orEmpty()
        val candidate = if (raw.isNotEmpty() && !isRawId(raw)) {
            cleanKnownModelStrings(raw)
        } else if (!deviceId.isNullOrBlank()) {
            cleanKnownModelStrings(deviceId.trim())
        } else {
            "Tracker"
        }

        val cleaned = if (isRawId(candidate)) {
            formatShortId(candidate)
        } else {
            candidate
        }

        return if (cleaned.length > maxLength) {
            cleaned.take(maxLength - 1) + "…"
        } else {
            cleaned
        }
    }

    /**
     * Formats a device model into a clean, concise hardware/model label without duplicate tags.
     * E.g.
     * - "Google sdk_gphone16k_x86_64 (A7A0)" -> "Pixel Emulator"
     * - "Samsung SM-A156M (497E)" -> "SM-A156M"
     * - "TECNO TECNO KL4 (86D6)" -> "Tecno KL4"
     * - "Android Tracker" -> "Android Tracker"
     */
    fun formatShortModel(model: String?): String {
        if (model.isNullOrBlank()) return "Android Tracker"
        val trimmed = model.trim().replace(PARENTHETICAL_TAG_REGEX, "").trim()
        if (trimmed.contains("sdk_gphone", ignoreCase = true) || trimmed.contains("emulator", ignoreCase = true)) {
            return "Pixel Emulator"
        }
        val samsungMatch = SAMSUNG_SHORT_MODEL_REGEX.find(trimmed)
        if (samsungMatch != null) {
            return "SM-${samsungMatch.groupValues[1]}"
        }
        val deduplicated = deduplicateWords(trimmed)
        return if (deduplicated.length > 18) {
            deduplicated.take(17) + "…"
        } else {
            deduplicated
        }
    }

    /**
     * Shortens a device ID for display badges (e.g. "A7A0" or "C3D4E5F6").
     */
    fun formatShortId(deviceId: String?): String {
        if (deviceId.isNullOrBlank()) return "Unknown"
        val trimmed = deviceId.trim()
        val tagMatch = TAG_EXTRACTION_REGEX.find(trimmed)
        if (tagMatch != null) {
            return tagMatch.groupValues[1]
        }
        val cleaned = trimmed.replace("-", "")
        return if (cleaned.length > 8) {
            cleaned.take(8).uppercase()
        } else {
            trimmed
        }
    }

    private fun isRawId(text: String): Boolean {
        if (text.length >= 32 && text.count { it == '-' } >= 3) return true
        if (text.startsWith("urn:uuid:", ignoreCase = true)) return true
        return false
    }

    private fun cleanKnownModelStrings(name: String): String {
        val suffixTag = TAG_SUFFIX_EXTRACTION_REGEX.find(name)?.groupValues?.getOrNull(1)
        val nameWithoutTag = name.replace(PARENTHETICAL_TAG_REGEX, "").trim()

        // 1. Emulators
        if (nameWithoutTag.contains("sdk_gphone", ignoreCase = true)) {
            return if (suffixTag != null) "Pixel ($suffixTag)" else "Pixel Emulator"
        }
        if (nameWithoutTag.contains("emulator", ignoreCase = true)) {
            return if (suffixTag != null) "Emulator ($suffixTag)" else "Emulator"
        }

        // 2. Samsung models: e.g. Samsung SM-A156M -> Samsung A15
        val samsungMatch = SAMSUNG_FULL_MODEL_REGEX.find(nameWithoutTag)
        if (samsungMatch != null) {
            val series = samsungMatch.groupValues[1].uppercase()
            val number = samsungMatch.groupValues[2]
            val base = "Samsung $series$number"
            return if (suffixTag != null) "$base ($suffixTag)" else base
        }

        // 3. Deduplicate repeating words (e.g. "TECNO TECNO KL4" -> "Tecno KL4")
        val deduplicated = deduplicateWords(nameWithoutTag)

        return if (suffixTag != null) {
            "$deduplicated ($suffixTag)"
        } else {
            deduplicated
        }
    }

    private fun deduplicateWords(text: String): String {
        val words = text.split(WHITESPACE_REGEX)
        val result = mutableListOf<String>()
        for (w in words) {
            if (result.isEmpty() || !result.last().equals(w, ignoreCase = true)) {
                // Capitalize first letter if all uppercase
                val formattedWord = if (w.length > 1 && w.all { it.isUpperCase() }) {
                    w.lowercase().replaceFirstChar { it.uppercase() }
                } else {
                    w
                }
                result.add(formattedWord)
            }
        }
        return result.joinToString(" ")
    }
}
