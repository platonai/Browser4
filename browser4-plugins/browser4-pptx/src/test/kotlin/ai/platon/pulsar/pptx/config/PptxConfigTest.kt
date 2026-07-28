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
package ai.platon.pulsar.pptx.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [PptxConfig] data class.
 */
class PptxConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = PptxConfig()

        assertEquals("downloads/pptx", config.outputDir)
        assertEquals(10 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(30, config.downloadTimeoutSeconds)
        assertEquals(3, config.concurrentDownloads)
        assertFalse(config.autoGenerateEnabled)
        assertEquals(120, config.maxTitleLength)
        assertEquals(720, config.slideWidth)
        assertEquals(540, config.slideHeight)
        assertEquals(2, config.maxImagesPerSlide)
        assertEquals(6, config.maxContentBlocksPerSlide)
        assertTrue(config.skipSvg)
        assertTrue(config.skipDataUris)
    }

    @Test
    @DisplayName("config accepts custom values")
    fun testCustomConfig() {
        val config = PptxConfig(
            outputDir = "/data/pptx",
            maxDownloadSize = 50 * 1024 * 1024L,
            downloadTimeoutSeconds = 60,
            concurrentDownloads = 5,
            autoGenerateEnabled = true,
            maxTitleLength = 200,
            slideWidth = 960,
            slideHeight = 540,
            maxImagesPerSlide = 4,
            maxContentBlocksPerSlide = 10,
            skipSvg = false,
            skipDataUris = false,
        )

        assertEquals("/data/pptx", config.outputDir)
        assertEquals(50 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(60, config.downloadTimeoutSeconds)
        assertEquals(5, config.concurrentDownloads)
        assertTrue(config.autoGenerateEnabled)
        assertEquals(200, config.maxTitleLength)
        assertEquals(960, config.slideWidth)
        assertEquals(540, config.slideHeight)
        assertEquals(4, config.maxImagesPerSlide)
        assertEquals(10, config.maxContentBlocksPerSlide)
        assertFalse(config.skipSvg)
        assertFalse(config.skipDataUris)
    }

    @Test
    @DisplayName("copy preserves defaults")
    fun testCopyPreservesDefaults() {
        val config = PptxConfig()
        val modified = config.copy(outputDir = "/custom/output")

        assertEquals("/custom/output", modified.outputDir)
        assertEquals(720, modified.slideWidth) // unchanged
        assertEquals(540, modified.slideHeight) // unchanged
        assertEquals(config.maxDownloadSize, modified.maxDownloadSize)
        assertEquals(config.concurrentDownloads, modified.concurrentDownloads)
    }
}
