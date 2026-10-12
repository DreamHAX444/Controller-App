package com.livetracker.controller.core.update.provider

import com.livetracker.controller.core.update.model.UpdateManifest

/**
 * Abstraction for retrieving release metadata from an online source.
 */
interface UpdateProvider {
    /**
     * Fetches and parses the latest update manifest from the specified endpoint URL.
     */
    suspend fun fetchManifest(manifestUrl: String): Result<UpdateManifest>
}
