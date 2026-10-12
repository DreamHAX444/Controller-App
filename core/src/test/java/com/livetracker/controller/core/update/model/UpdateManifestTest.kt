package com.livetracker.controller.core.update.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseValidManifest_succeedsWithAllFields() {
        val jsonStr = """
            {
              "schemaVersion": 1,
              "appId": "com.livetracker.controller",
              "channel": "stable",
              "versionCode": 42,
              "versionName": "1.4.2",
              "minimumSupportedVersionCode": 1,
              "mandatory": true,
              "publishedAt": "2026-10-12T10:00:00Z",
              "releaseNotes": "Critical security and telemetry updates.",
              "apkUrl": "https://example.com/release.apk",
              "apkSizeBytes": 25000000,
              "sha256": "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72",
              "signingCertSha256": "b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2"
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(jsonStr)

        assertEquals(1, manifest.schemaVersion)
        assertEquals("com.livetracker.controller", manifest.appId)
        assertEquals("stable", manifest.channel)
        assertEquals(42L, manifest.versionCode)
        assertEquals("1.4.2", manifest.versionName)
        assertTrue(manifest.mandatory)
        assertEquals(25000000L, manifest.apkSizeBytes)
        assertEquals("681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72", manifest.sha256)
        assertEquals("b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2", manifest.signingCertSha256)
    }

    @Test
    fun blankAppId_throwsException() {
        try {
            UpdateManifest(
                schemaVersion = 1,
                appId = "",
                channel = "stable",
                versionCode = 2,
                versionName = "1.1",
                publishedAt = "2026-10-12T10:00:00Z",
                releaseNotes = "Notes",
                apkUrl = "https://example.com/app.apk",
                apkSizeBytes = 1000,
                sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
            )
            fail("Expected IllegalArgumentException for blank appId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("appId") == true)
        }
    }

    @Test
    fun invalidSha256Length_throwsException() {
        try {
            UpdateManifest(
                schemaVersion = 1,
                appId = "com.livetracker.controller",
                channel = "stable",
                versionCode = 2,
                versionName = "1.1",
                publishedAt = "2026-10-12T10:00:00Z",
                releaseNotes = "Notes",
                apkUrl = "https://example.com/app.apk",
                apkSizeBytes = 1000,
                sha256 = "tooshort"
            )
            fail("Expected IllegalArgumentException for invalid sha256 length")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("sha256") == true)
        }
    }

    @Test
    fun zeroVersionCode_throwsException() {
        try {
            UpdateManifest(
                schemaVersion = 1,
                appId = "com.livetracker.controller",
                channel = "stable",
                versionCode = 0,
                versionName = "1.0",
                publishedAt = "2026-10-12T10:00:00Z",
                releaseNotes = "Notes",
                apkUrl = "https://example.com/app.apk",
                apkSizeBytes = 1000,
                sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
            )
            fail("Expected IllegalArgumentException for 0 versionCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("versionCode") == true)
        }
    }

    @Test
    fun insecureHttpUrl_throwsException() {
        try {
            UpdateManifest(
                schemaVersion = 1,
                appId = "com.livetracker.controller",
                channel = "stable",
                versionCode = 2,
                versionName = "1.0.0",
                publishedAt = "2026-10-12T10:00:00Z",
                releaseNotes = "Notes",
                apkUrl = "http://insecure.example.com/app.apk",
                apkSizeBytes = 1000,
                sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72"
            )
            fail("Expected IllegalArgumentException for insecure HTTP URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("HTTPS") == true)
        }
    }

    @Test
    fun nonHexSha256_throwsException() {
        try {
            UpdateManifest(
                schemaVersion = 1,
                appId = "com.livetracker.controller",
                channel = "stable",
                versionCode = 2,
                versionName = "1.0.0",
                publishedAt = "2026-10-12T10:00:00Z",
                releaseNotes = "Notes",
                apkUrl = "https://example.com/app.apk",
                apkSizeBytes = 1000,
                sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602aXX"
            )
            fail("Expected IllegalArgumentException for non-hex sha256")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("hexadecimal") == true)
        }
    }

    @Test
    fun parseV1ReleaseManifest_matchesProductionFormat() {
        val jsonStr = """
            {
                "schemaVersion": 1,
                "appId": "com.livetracker.controller",
                "channel": "stable",
                "versionCode": 1,
                "versionName": "1.0.0",
                "minimumSupportedVersionCode": 1,
                "mandatory": false,
                "publishedAt": "2026-10-12T14:01:30Z",
                "releaseNotes": "Trace Controller v1.0.0 stable release.",
                "apkUrl": "https://github.com/DreamHAX444/Controller-App/releases/download/v1.0.0/app-release.apk",
                "apkSizeBytes": 44086985,
                "sha256": "ff63144cdec0eda51d44514b942256dec587608854f83ce52a746b6e125b4a30",
                "signingCertSha256": "b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2"
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(jsonStr)
        assertEquals(1, manifest.schemaVersion)
        assertEquals("com.livetracker.controller", manifest.appId)
        assertEquals(1L, manifest.versionCode)
        assertEquals("1.0.0", manifest.versionName)
        assertEquals("https://github.com/DreamHAX444/Controller-App/releases/download/v1.0.0/app-release.apk", manifest.apkUrl)
        assertEquals(44086985L, manifest.apkSizeBytes)
    }
}
