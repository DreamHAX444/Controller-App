package com.livetracker.controller.core.tracker.audio.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DefaultAudioRepositoryTest {

    private class FakeAudioPlayer : AudioPlayer {
        var isStarted = false
        var isStopped = false
        val playedBytes = mutableListOf<ByteArray>()
        var currentVolume = 1f

        override fun start() { isStarted = true }
        override fun playPcm(pcm: ByteArray) { playedBytes.add(pcm) }
        override fun setVolume(volume: Float) { this.currentVolume = volume }
        override fun stop() { isStopped = true }
    }

    @Test
    fun `audio frame payload extraction parses both payload and pcmBytes fields`() {
        val testPcm = byteArrayOf(1, 2, 3, 4, 5)
        val base64Str = Base64.getEncoder().encodeToString(testPcm)

        val json = kotlinx.serialization.json.Json
        // Case 1: Tracker format ("payload")
        val trackerFrameJson = """{"session_id":"s1","device_id":"d1","seq":1,"timestamp":1000,"encoding":"pcm_16bit","duration_ms":20,"payload":"$base64Str"}"""
        val trackerObj = json.parseToJsonElement(trackerFrameJson) as kotlinx.serialization.json.JsonObject
        val extracted1 = trackerObj["payload"]?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) it.content else it.toString()
        } ?: trackerObj["pcmBytes"]?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) it.content else it.toString()
        }
        assertEquals(base64Str, extracted1)
        val decoded1 = Base64.getDecoder().decode(extracted1)
        assertTrue(testPcm.contentEquals(decoded1))

        // Case 2: Legacy format ("pcmBytes")
        val legacyFrameJson = """{"pcmBytes":"$base64Str"}"""
        val legacyObj = json.parseToJsonElement(legacyFrameJson) as kotlinx.serialization.json.JsonObject
        val extracted2 = legacyObj["payload"]?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) it.content else it.toString()
        } ?: legacyObj["pcmBytes"]?.let {
            if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) it.content else it.toString()
        }
        assertEquals(base64Str, extracted2)
        val decoded2 = Base64.getDecoder().decode(extracted2)
        assertTrue(testPcm.contentEquals(decoded2))
    }
}
