package com.livetracker.controller.core.auth.domain

/**
 * Maps between domain [ServicePermission] and database feature strings
 * configured in TRACE Phase 2A/5 schema (LOCATION, CAMERA, FILES, MIC, SCREEN, COMMANDS).
 */
object FeaturePermissionMapper {
    const val FEATURE_LOCATION = "LOCATION"
    const val FEATURE_CAMERA = "CAMERA"
    const val FEATURE_FILES = "FILES"
    const val FEATURE_MIC = "MIC"
    const val FEATURE_SCREEN = "SCREEN"
    const val FEATURE_COMMANDS = "COMMANDS"

    val ALL_DATABASE_FEATURES: Set<String> = setOf(
        FEATURE_LOCATION,
        FEATURE_CAMERA,
        FEATURE_FILES,
        FEATURE_MIC,
        FEATURE_SCREEN,
        FEATURE_COMMANDS
    )

    /**
     * Maps a domain [ServicePermission] to its database feature string representation.
     */
    fun toDatabaseFeature(permission: ServicePermission): String {
        return when (permission) {
            ServicePermission.LOCATION -> FEATURE_LOCATION
            ServicePermission.CAMERA -> FEATURE_CAMERA
            ServicePermission.MICROPHONE_AUDIO,
            ServicePermission.DEVICE_AUDIO -> FEATURE_MIC
            ServicePermission.SCREEN_CAPTURE -> FEATURE_SCREEN
            ServicePermission.FILE_READ,
            ServicePermission.FILE_WRITE,
            ServicePermission.FILE_DELETE,
            ServicePermission.FILE_RENAME,
            ServicePermission.FILE_TRANSFER -> FEATURE_FILES
        }
    }

    /**
     * Maps a database feature string to the set of domain [ServicePermission]s it unlocks.
     * Note: Returns an empty set for unknown or unmapped features (e.g. COMMANDS which operates
     * at execution gateway level).
     */
    fun toServicePermissions(dbFeature: String): Set<ServicePermission> {
        return when (dbFeature.trim().uppercase()) {
            FEATURE_LOCATION -> setOf(ServicePermission.LOCATION)
            FEATURE_CAMERA -> setOf(ServicePermission.CAMERA)
            FEATURE_MIC -> setOf(ServicePermission.MICROPHONE_AUDIO, ServicePermission.DEVICE_AUDIO)
            FEATURE_SCREEN -> setOf(ServicePermission.SCREEN_CAPTURE)
            FEATURE_FILES -> setOf(
                ServicePermission.FILE_READ,
                ServicePermission.FILE_WRITE,
                ServicePermission.FILE_DELETE,
                ServicePermission.FILE_RENAME,
                ServicePermission.FILE_TRANSFER
            )
            FEATURE_COMMANDS -> emptySet()
            else -> emptySet()
        }
    }

    /**
     * Converts a collection of database feature strings into a consolidated set of [ServicePermission]s.
     */
    fun toServicePermissions(dbFeatures: Iterable<String>): Set<ServicePermission> {
        return dbFeatures.flatMapTo(mutableSetOf()) { toServicePermissions(it) }
    }

    /**
     * Checks if a string is a valid recognized database feature constraint value.
     */
    fun isValidDatabaseFeature(feature: String?): Boolean {
        if (feature.isNullOrBlank()) return false
        return feature.trim().uppercase() in ALL_DATABASE_FEATURES
    }
}

/**
 * Extension helper to map a domain [ServicePermission] directly to its database feature string.
 */
fun ServicePermission.toDatabaseFeature(): String = FeaturePermissionMapper.toDatabaseFeature(this)

/**
 * Extension helper to map a database feature string directly to its corresponding [ServicePermission] set.
 */
fun String.toServicePermissions(): Set<ServicePermission> = FeaturePermissionMapper.toServicePermissions(this)
