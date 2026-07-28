/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.media.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [MediaConfig] data class.
 */
class MediaConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = MediaConfig()

        assertEquals("ffmpeg", config.ffmpegPath)
        assertEquals("ffprobe", config.ffprobePath)
        assertEquals("downloads/media", config.downloadDir)
        assertEquals(500 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(300, config.downloadTimeoutSeconds)
        assertEquals(600, config.ffmpegTimeoutSeconds)
        assertFalse(config.autoDetectEnabled)
        assertEquals(3, config.concurrentDownloads)
    }

    @Test
    @DisplayName("config accepts custom values")
    fun testCustomConfig() {
        val config = MediaConfig(
            ffmpegPath = "/usr/local/bin/ffmpeg",
            ffprobePath = "/usr/local/bin/ffprobe",
            downloadDir = "/data/videos",
            maxDownloadSize = 1024 * 1024 * 1024L, // 1 GB
            downloadTimeoutSeconds = 600,
            ffmpegTimeoutSeconds = 1200,
            autoDetectEnabled = true,
            concurrentDownloads = 5,
        )

        assertEquals("/usr/local/bin/ffmpeg", config.ffmpegPath)
        assertEquals("/usr/local/bin/ffprobe", config.ffprobePath)
        assertEquals("/data/videos", config.downloadDir)
        assertEquals(1024 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(600, config.downloadTimeoutSeconds)
        assertEquals(1200, config.ffmpegTimeoutSeconds)
        assertTrue(config.autoDetectEnabled)
        assertEquals(5, config.concurrentDownloads)
    }

    @Test
    @DisplayName("copy preserves defaults")
    fun testCopyPreservesDefaults() {
        val config = MediaConfig()
        val modified = config.copy(ffmpegPath = "/opt/ffmpeg")

        assertEquals("/opt/ffmpeg", modified.ffmpegPath)
        assertEquals("ffprobe", modified.ffprobePath) // unchanged
        assertEquals(config.downloadDir, modified.downloadDir)
    }
}
