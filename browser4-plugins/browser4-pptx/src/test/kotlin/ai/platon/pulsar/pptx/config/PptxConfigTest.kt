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

import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [PptxConfig] data class.
 */
class PptxConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = PptxConfig()

        // The default output dir is absolute and anchored under the user's
        // ~/.browser4 state dir, never a CWD-relative "downloads/pptx".
        assertEquals(PptxConfig.defaultOutputDir(), config.outputDir)
        assertTrue(Path.of(config.outputDir).isAbsolute)
        assertEquals(10 * 1024 * 1024L, config.maxDownloadSize)
        assertEquals(30, config.downloadTimeoutSeconds)
        assertEquals(60, config.downloadTotalTimeoutSeconds)
        assertEquals(3, config.concurrentDownloads)
        assertFalse(config.autoGenerateEnabled)
        assertEquals(120, config.maxTitleLength)
        assertEquals(720, config.slideWidth)
        assertEquals(540, config.slideHeight)
        assertEquals(2, config.maxImagesPerSlide)
        assertEquals(6, config.maxContentBlocksPerSlide)
        assertTrue(config.skipSvg)
        assertTrue(config.skipDataUris)
        // proxy defaults to null — falls back to HTTPS_PROXY/HTTP_PROXY env vars,
        // the JVM proxy properties and the OS system proxy.
        assertNull(config.proxy)
        assertTrue(config.useSystemProxy)
        assertTrue(config.excludeSelectors.isEmpty())
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

    @Test
    @DisplayName("defaultOutputDir is anchored at ~/.browser4/downloads/pptx")
    fun testDefaultOutputDirIsUnderUserHome() {
        val expected = Path.of(System.getProperty("user.home"), ".browser4", "downloads", "pptx")
        assertEquals(expected.toString(), PptxConfig.defaultOutputDir())
    }

    @Test
    @DisplayName("fromConfig falls back to the absolute default output dir and honours the override")
    fun testFromConfigOutputDir() {
        assertEquals(PptxConfig.defaultOutputDir(), PptxConfig.fromConfig(MutableConfig()).outputDir)

        val conf = MutableConfig()
        conf.set("pptx.output.dir", "/data/pptx")
        assertEquals("/data/pptx", PptxConfig.fromConfig(conf).outputDir)
    }

    @Test
    @DisplayName("fromConfig reads the download deadline, system proxy flag and exclude selectors")
    fun testFromConfigReadsDeadlineSystemProxyAndSelectors() {
        val conf = MutableConfig()
        conf.set("pptx.download.total-timeout.seconds", "90")
        conf.set("pptx.download.use-system-proxy", "false")
        conf.set("pptx.detect.exclude-selectors", ".ad-banner, #sponsor ,")

        val config = PptxConfig.fromConfig(conf)

        assertEquals(90, config.downloadTotalTimeoutSeconds)
        assertFalse(config.useSystemProxy)
        assertEquals(listOf(".ad-banner", "#sponsor"), config.excludeSelectors)
    }

    @Test
    @DisplayName("fromConfig maps an empty exclude-selectors value to an empty list")
    fun testFromConfigMapsBlankExcludeSelectorsToEmptyList() {
        val conf = MutableConfig()
        conf.set("pptx.detect.exclude-selectors", "  ")

        assertTrue(PptxConfig.fromConfig(conf).excludeSelectors.isEmpty())
    }
}
