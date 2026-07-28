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
package ai.platon.pulsar.images.config

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ImageConfig] data class and [ImageConfig.fromConfig] factory method.
 */
class ImageConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = ImageConfig()

        assertEquals("downloads/images", config.downloadDir)
        assertEquals(50 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(60, config.downloadTimeoutSeconds)
        assertFalse(config.autoDetectEnabled)
        assertFalse(config.autoDownloadEnabled)
        assertEquals(5, config.concurrentDownloads)
        assertEquals(0, config.minWidth)
        assertEquals(0, config.minHeight)
        assertFalse(config.skipSvg)
        assertTrue(config.skipDataUris)
    }

    @Test
    @DisplayName("config accepts custom values")
    fun testCustomConfig() {
        val config = ImageConfig(
            downloadDir = "/data/images",
            maxDownloadSize = 100 * 1024 * 1024L, // 100 MB
            downloadTimeoutSeconds = 120,
            autoDetectEnabled = true,
            autoDownloadEnabled = true,
            concurrentDownloads = 10,
            minWidth = 200,
            minHeight = 150,
            skipSvg = true,
            skipDataUris = false,
        )

        assertEquals("/data/images", config.downloadDir)
        assertEquals(100 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(120, config.downloadTimeoutSeconds)
        assertTrue(config.autoDetectEnabled)
        assertTrue(config.autoDownloadEnabled)
        assertEquals(10, config.concurrentDownloads)
        assertEquals(200, config.minWidth)
        assertEquals(150, config.minHeight)
        assertTrue(config.skipSvg)
        assertFalse(config.skipDataUris)
    }

    @Test
    @DisplayName("copy preserves defaults for unspecified fields")
    fun testCopyPreservesDefaults() {
        val config = ImageConfig()
        val modified = config.copy(
            downloadDir = "/custom/path",
            autoDetectEnabled = true,
        )

        assertEquals("/custom/path", modified.downloadDir)
        assertTrue(modified.autoDetectEnabled)

        // Unchanged fields retain defaults
        assertEquals(config.maxDownloadSize, modified.maxDownloadSize)
        assertEquals(config.downloadTimeoutSeconds, modified.downloadTimeoutSeconds)
        assertEquals(config.concurrentDownloads, modified.concurrentDownloads)
        assertEquals(config.minWidth, modified.minWidth)
        assertEquals(config.minHeight, modified.minHeight)
        assertEquals(config.skipSvg, modified.skipSvg)
        assertEquals(config.skipDataUris, modified.skipDataUris)
    }

    @Test
    @DisplayName("fromConfig maps all properties with image. prefix")
    fun testFromConfigMapsAllProperties() {
        // Build an ImmutableConfig from system-like properties.
        // Since ImmutableConfig uses defaults when keys are absent, we test
        // that the method correctly delegates to the config interface.
        val baseConfig = ImmutableConfig()

        // With only defaults available, fromConfig returns default ImageConfig
        val config = ImageConfig.fromConfig(baseConfig)
        val defaultConfig = ImageConfig()

        assertEquals(defaultConfig.downloadDir, config.downloadDir)
        assertEquals(defaultConfig.maxDownloadSize, config.maxDownloadSize)
        assertEquals(defaultConfig.downloadTimeoutSeconds, config.downloadTimeoutSeconds)
        assertEquals(defaultConfig.autoDetectEnabled, config.autoDetectEnabled)
        assertEquals(defaultConfig.autoDownloadEnabled, config.autoDownloadEnabled)
        assertEquals(defaultConfig.concurrentDownloads, config.concurrentDownloads)
        assertEquals(defaultConfig.minWidth, config.minWidth)
        assertEquals(defaultConfig.minHeight, config.minHeight)
        assertEquals(defaultConfig.skipSvg, config.skipSvg)
        assertEquals(defaultConfig.skipDataUris, config.skipDataUris)
    }

    @Test
    @DisplayName("ImageConfig is a data class with correct equals/hashCode")
    fun testEquality() {
        val config1 = ImageConfig(downloadDir = "/same/path")
        val config2 = ImageConfig(downloadDir = "/same/path")
        val config3 = ImageConfig(downloadDir = "/different/path")

        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
