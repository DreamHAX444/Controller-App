package com.livetracker.controller.ui.screens.map.offline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Hard safety limits for offline map downloading to prevent app crashes,
 * memory exhaustion, disk overflow, and tile server rate-limiting.
 */
object OfflineMapConstants {
    const val MAX_OFFLINE_TILES_LIMIT = 5000
    const val ESTIMATED_BYTES_PER_TILE = 25L * 1024L // ~25 KB average compressed WebP/PNG tile
    const val DEFAULT_MIN_ZOOM = 12
    const val DEFAULT_MAX_ZOOM = 16
    const val MIN_DEVICE_FREE_SPACE_BYTES = 200L * 1024L * 1024L // 200 MB safe margin
    const val PARALLEL_DOWNLOAD_WORKERS = 2 // Respects OpenStreetMap max 2-thread policy
}

/**
 * State representing an offline map download operation.
 */
sealed interface OfflineDownloadState {
    data object Idle : OfflineDownloadState

    data class Estimating(
        val totalTiles: Int,
        val estimatedSizeBytes: Long
    ) : OfflineDownloadState

    data class Downloading(
        val progress: Float, // 0.0f to 1.0f
        val currentTiles: Int,
        val totalTiles: Int,
        val failedTiles: Int = 0
    ) : OfflineDownloadState

    data class Completed(
        val totalTiles: Int,
        val totalSizeBytes: Long
    ) : OfflineDownloadState

    data class Error(val message: String) : OfflineDownloadState

    data object Cancelled : OfflineDownloadState
}

/**
 * Information regarding on-disk offline map cache consumption.
 */
data class OfflineCacheInfo(
    val usedBytes: Long = 0L,
    val maxBytes: Long = 300L * 1024 * 1024,
    val tileCount: Long = 0L
) {
    val usedMb: Float get() = (usedBytes / (1024f * 1024f))
    val maxMb: Float get() = (maxBytes / (1024f * 1024f))
    val usagePercentage: Float get() = if (maxBytes > 0) (usedBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * User-configurable offline map and cache preferences.
 */
data class OfflineMapPreferences(
    val isOfflineDownloadAllowed: Boolean = true,
    val autoCacheTrackerRegions: Boolean = false,
    val maxCacheSizeMb: Int = 300
)

/**
 * Bounding box representation for an offline region.
 */
data class OfflineRegion(
    val name: String,
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
    val minZoom: Int = OfflineMapConstants.DEFAULT_MIN_ZOOM,
    val maxZoom: Int = OfflineMapConstants.DEFAULT_MAX_ZOOM
) {
    /**
     * Fast mathematical calculation of total tiles within this region
     * across the zoom level range using standard Web Mercator projection.
     */
    fun calculateTileCount(): Int {
        return calculateTileCountForBounds(
            north = north,
            east = east,
            south = south,
            west = west,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    val estimatedSizeBytes: Long
        get() = calculateTileCount() * OfflineMapConstants.ESTIMATED_BYTES_PER_TILE

    val isWithinSafeLimit: Boolean
        get() = calculateTileCount() <= OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT
}

/**
 * Persistently stored metadata for an explicitly downloaded offline map region.
 * Retained in private persistent storage across normal application restarts.
 */
data class SavedOfflineRegion(
    val id: String,
    val name: String,
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Int,
    val sizeBytes: Long,
    val downloadTimestamp: Long,
    val isComplete: Boolean = true
) {
    val sizeMb: Float get() = (sizeBytes / (1024f * 1024f))
}

/**
 * Fast, pure-Kotlin mathematical Web Mercator tile count estimator (< 0.1 ms execution).
 */
fun calculateTileCountForBounds(
    north: Double,
    east: Double,
    south: Double,
    west: Double,
    minZoom: Int = OfflineMapConstants.DEFAULT_MIN_ZOOM,
    maxZoom: Int = OfflineMapConstants.DEFAULT_MAX_ZOOM
): Int {
    var total = 0
    val nClamped = north.coerceIn(-85.05112878, 85.05112878)
    val sClamped = south.coerceIn(-85.05112878, 85.05112878)
    val eClamped = east.coerceIn(-180.0, 180.0)
    val wClamped = west.coerceIn(-180.0, 180.0)

    for (zoom in minZoom..maxZoom) {
        val numTiles = 1 shl zoom

        val xMin = floor((wClamped + 180.0) / 360.0 * numTiles).toInt().coerceIn(0, numTiles - 1)
        val xMax = floor((eClamped + 180.0) / 360.0 * numTiles).toInt().coerceIn(0, numTiles - 1)

        val yMin = floor((1.0 - ln(tan(Math.toRadians(nClamped)) + 1.0 / cos(Math.toRadians(nClamped))) / Math.PI) / 2.0 * numTiles).toInt().coerceIn(0, numTiles - 1)
        val yMax = floor((1.0 - ln(tan(Math.toRadians(sClamped)) + 1.0 / cos(Math.toRadians(sClamped))) / Math.PI) / 2.0 * numTiles).toInt().coerceIn(0, numTiles - 1)

        val xCount = abs(xMax - xMin) + 1
        val yCount = abs(yMax - yMin) + 1
        total += (xCount * yCount)
    }
    return total
}
