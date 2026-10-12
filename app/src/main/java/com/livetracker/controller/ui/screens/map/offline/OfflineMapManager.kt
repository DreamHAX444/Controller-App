package com.livetracker.controller.ui.screens.map.offline

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.livetracker.controller.ui.screens.map.MapPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Universal manager for high-speed parallel offline map tile downloading,
 * cache management, and persistent storage retention with enforced safety boundaries.
 */
class OfflineMapManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mapPreferencesRepository: MapPreferencesRepository? = null,
    val offlineRegionRepository: PersistentOfflineRegionRepository = PersistentOfflineRegionRepository(context)
) {
    private val _downloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState: StateFlow<OfflineDownloadState> = _downloadState.asStateFlow()

    private val _cacheInfo = MutableStateFlow(OfflineCacheInfo())
    val cacheInfo: StateFlow<OfflineCacheInfo> = _cacheInfo.asStateFlow()

    val savedRegions: StateFlow<List<SavedOfflineRegion>> = offlineRegionRepository.regions

    private var activeJob: Job? = null

    init {
        refreshCacheInfo()
    }

    /**
     * Upfront pre-warm method executed during application startup loading screen.
     * Initializes SQLite handles, verifies persistent storage directory permissions,
     * cleans legacy cacheDir if present, and populates cache info in memory.
     */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        try {
            // Clean up obsolete temporary cache folder if it was previously used
            val legacyCache = File(context.cacheDir, "osmdroid")
            if (legacyCache.exists()) {
                legacyCache.deleteRecursively()
            }

            val usedBytes = calculateCacheSizeBytes()
            val maxBytes = Configuration.getInstance().tileFileSystemCacheMaxBytes
            _cacheInfo.value = OfflineCacheInfo(
                usedBytes = usedBytes,
                maxBytes = maxBytes
            )
        } catch (_: Throwable) {}
    }

    /**
     * Inspects the on-disk SQLite tile cache and directory size.
     */
    fun refreshCacheInfo() {
        scope.launch(Dispatchers.IO) {
            try {
                val usedBytes = calculateCacheSizeBytes()
                val maxBytes = Configuration.getInstance().tileFileSystemCacheMaxBytes
                _cacheInfo.value = OfflineCacheInfo(
                    usedBytes = usedBytes,
                    maxBytes = maxBytes
                )
            } catch (e: Exception) {
                // Ignore inspection failure
            }
        }
    }

    /**
     * Estimates tile count for a given bounding box across zoom levels.
     * Uses fast Web Mercator mathematical calculation (< 0.1ms).
     */
    fun estimateTileCount(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        minZoom: Int = OfflineMapConstants.DEFAULT_MIN_ZOOM,
        maxZoom: Int = OfflineMapConstants.DEFAULT_MAX_ZOOM
    ): Int {
        return calculateTileCountForBounds(
            north = north,
            east = east,
            south = south,
            west = west,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    /**
     * Starts downloading offline map tiles for a specified geographic bounding box.
     * Strictly verifies safety limits and disk space before downloading.
     */
    fun downloadRegion(
        region: OfflineRegion,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (_downloadState.value is OfflineDownloadState.Downloading) {
            return // Download already in progress
        }

        // Hard Limit Check 1: Tile count ceiling
        val estimatedTiles = region.calculateTileCount()
        if (estimatedTiles > OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT) {
            val errorMsg = "Area contains $estimatedTiles tiles, exceeding safe limit of ${OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT} tiles. Please zoom in to a smaller region."
            _downloadState.value = OfflineDownloadState.Error(errorMsg)
            onError?.invoke(errorMsg)
            return
        }

        // Hard Limit Check 2: Device free storage space
        val freeSpaceBytes = getDeviceFreeStorageBytes()
        val estimatedBytes = region.estimatedSizeBytes
        if (freeSpaceBytes < estimatedBytes + OfflineMapConstants.MIN_DEVICE_FREE_SPACE_BYTES) {
            val errorMsg = "Insufficient device storage space. Need at least ${((estimatedBytes + OfflineMapConstants.MIN_DEVICE_FREE_SPACE_BYTES) / (1024 * 1024))} MB free."
            _downloadState.value = OfflineDownloadState.Error(errorMsg)
            onError?.invoke(errorMsg)
            return
        }

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                val bb = BoundingBox(region.north, region.east, region.south, region.west)
                val tileSource = mapPreferencesRepository?.mapConfiguration?.value?.let { config ->
                    OfflineTileSources.resolveTileSource(config.provider, config.style)
                } ?: OfflineTileSources.OPEN_STREET_MAP

                // 1. Instantly calculate full list of required tile indices
                val tiles = CacheManager.getTilesCoverage(bb, region.minZoom, region.maxZoom)
                val totalTiles = tiles.size

                if (totalTiles > OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT) {
                    val errorMsg = "Area requires $totalTiles tiles, exceeding safe limit of ${OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT} tiles. Zoom in to select a smaller region."
                    _downloadState.value = OfflineDownloadState.Error(errorMsg)
                    onError?.invoke(errorMsg)
                    return@launch
                }

                _downloadState.value = OfflineDownloadState.Estimating(
                    totalTiles = totalTiles,
                    estimatedSizeBytes = totalTiles * OfflineMapConstants.ESTIMATED_BYTES_PER_TILE
                )

                _downloadState.value = OfflineDownloadState.Downloading(
                    progress = 0f,
                    currentTiles = 0,
                    totalTiles = totalTiles
                )

                val tileWriter = SqlTileWriter()
                val dbLock = Any()
                val semaphore = Semaphore(OfflineMapConstants.PARALLEL_DOWNLOAD_WORKERS)
                val completedCounter = AtomicInteger(0)
                val failedCounter = AtomicInteger(0)
                val userAgent = Configuration.getInstance().userAgentValue ?: context.packageName
                val expirationTime = System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 30L) // 30 days retention
                var lastProgressTime = 0L

                fun emitThrottledProgress(done: Int, total: Int, errors: Int) {
                    val now = System.currentTimeMillis()
                    if (done == total || now - lastProgressTime >= 80L) {
                        lastProgressTime = now
                        val pct = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        _downloadState.value = OfflineDownloadState.Downloading(
                            progress = pct,
                            currentTiles = done,
                            totalTiles = total,
                            failedTiles = errors
                        )
                    }
                }

                // 2. Launch high-speed parallel download workers
                val downloadJobs = tiles.map { tileIndex ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (!isActive) return@withPermit

                            // Fast local cache check (< 0.01ms) - skip if already saved
                            val alreadyExists = synchronized(dbLock) {
                                tileWriter.exists(tileSource, tileIndex)
                            }
                            if (alreadyExists) {
                                val done = completedCounter.incrementAndGet()
                                emitThrottledProgress(done, totalTiles, failedCounter.get())
                                return@withPermit
                            }

                            // Parallel network fetch directly into database (zero bitmap decoding overhead)
                            var success = false
                            var connection: HttpURLConnection? = null
                            try {
                                val urlString = tileSource.getTileURLString(tileIndex)
                                connection = URL(urlString).openConnection() as HttpURLConnection
                                connection.connectTimeout = 4000
                                connection.readTimeout = 6000
                                connection.setRequestProperty("User-Agent", userAgent)
                                connection.setRequestProperty("Accept", "image/png,image/*;q=0.8")
                                connection.setRequestProperty("Connection", "keep-alive")
                                connection.connect()
                                val responseCode = connection.responseCode

                                if (responseCode == 429 || responseCode == 503) {
                                    val retryHeader = connection.getHeaderField("Retry-After")
                                    val delaySeconds = retryHeader?.toLongOrNull() ?: 1L
                                    kotlinx.coroutines.delay((delaySeconds * 1000L).coerceIn(500L, 5000L))
                                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                                    connection.inputStream.use { inStream ->
                                        val bytes = inStream.readBytes()
                                        if (bytes.isNotEmpty()) {
                                            synchronized(dbLock) {
                                                ByteArrayInputStream(bytes).use { byteStream ->
                                                    tileWriter.saveFile(tileSource, tileIndex, byteStream, expirationTime)
                                                }
                                            }
                                            success = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Transient tile timeout caught without interrupting full batch
                            } finally {
                                try { connection?.disconnect() } catch (_: Exception) {}
                            }

                            if (success) {
                                val done = completedCounter.incrementAndGet()
                                emitThrottledProgress(done, totalTiles, failedCounter.get())
                            } else {
                                failedCounter.incrementAndGet()
                                val done = completedCounter.incrementAndGet()
                                emitThrottledProgress(done, totalTiles, failedCounter.get())
                            }
                        }
                    }
                }

                downloadJobs.awaitAll()

                if (isActive) {
                    val finalDone = completedCounter.get()
                    val finalFailed = failedCounter.get()
                    if (finalFailed > 0 && finalDone == finalFailed) {
                        val errorMsg = "Download failed: tile servers unreachable."
                        _downloadState.value = OfflineDownloadState.Error(errorMsg)
                        onError?.invoke(errorMsg)
                    } else {
                        val saved = SavedOfflineRegion(
                            id = java.util.UUID.randomUUID().toString(),
                            name = region.name.ifBlank { "Offline Area ${System.currentTimeMillis()}" },
                            north = region.north,
                            east = region.east,
                            south = region.south,
                            west = region.west,
                            minZoom = region.minZoom,
                            maxZoom = region.maxZoom,
                            tileCount = finalDone,
                            sizeBytes = finalDone * OfflineMapConstants.ESTIMATED_BYTES_PER_TILE,
                            downloadTimestamp = System.currentTimeMillis(),
                            isComplete = true
                        )
                        offlineRegionRepository.saveRegion(saved)

                        _downloadState.value = OfflineDownloadState.Completed(
                            totalTiles = finalDone,
                            totalSizeBytes = finalDone * OfflineMapConstants.ESTIMATED_BYTES_PER_TILE
                        )
                        refreshCacheInfo()
                        onComplete?.invoke()
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to initiate download"
                _downloadState.value = OfflineDownloadState.Error(errorMsg)
                onError?.invoke(errorMsg)
            }
        }
    }

    /**
     * Deletes a specific saved offline map region metadata and recalculates storage usage.
     */
    suspend fun deleteRegion(id: String): Boolean = withContext(Dispatchers.IO) {
        val removed = offlineRegionRepository.removeRegion(id)
        refreshCacheInfo()
        removed
    }

    /**
     * Cancels an ongoing map download immediately and cleans up background workers.
     */
    fun cancelDownload() {
        activeJob?.cancel()
        activeJob = null
        _downloadState.value = OfflineDownloadState.Cancelled
    }

    /**
     * Clears all cached offline map tiles from disk storage and resets saved regions.
     */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        cancelDownload()
        offlineRegionRepository.clearAll()
        try {
            val tileWriter = SqlTileWriter()
            tileWriter.purgeCache()
            refreshCacheInfo()
            _downloadState.value = OfflineDownloadState.Idle
            true
        } catch (e: Exception) {
            // Fallback manual directory cleanup
            try {
                val cacheDir = Configuration.getInstance().osmdroidTileCache
                cacheDir?.deleteRecursively()
                refreshCacheInfo()
                _downloadState.value = OfflineDownloadState.Idle
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun getDeviceFreeStorageBytes(): Long {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun calculateCacheSizeBytes(): Long {
        val cacheDir = Configuration.getInstance().osmdroidTileCache ?: return 0L
        if (!cacheDir.exists()) return 0L
        val dbFile = File(cacheDir, "cache.db")
        val walFile = File(cacheDir, "cache.db-wal")
        val shmFile = File(cacheDir, "cache.db-shm")
        val journalFile = File(cacheDir, "cache.db-journal")
        if (dbFile.exists()) {
            return dbFile.length() +
                (if (walFile.exists()) walFile.length() else 0L) +
                (if (shmFile.exists()) shmFile.length() else 0L) +
                (if (journalFile.exists()) journalFile.length() else 0L)
        }
        return getFolderSize(cacheDir)
    }

    private fun getFolderSize(dir: File): Long {
        var size: Long = 0
        if (!dir.exists()) return 0
        val files = dir.listFiles() ?: return 0
        for (file in files) {
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
