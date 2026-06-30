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
package ai.platon.pulsar.media.integration

import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.media.config.MediaConfig
import ai.platon.pulsar.media.service.VideoDetector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Tests for [MediaBrowseEventHandler] — verifies auto-detection toggle and delegation.
 */
class MediaBrowseEventHandlerTest {

    private lateinit var config: MediaConfig

    @BeforeEach
    fun setUp() {
        config = MediaConfig(autoDetectEnabled = true)
    }

    @Test
    @DisplayName("handler does nothing when autoDetect disabled")
    fun testHandlerDisabled() = runBlocking {
        config = MediaConfig(autoDetectEnabled = false)
        val detector = object : VideoDetector() {
            override suspend fun detect(driver: WebDriver): List<VideoDetector.VideoSource> {
                error("Should not be called when disabled")
            }
        }
        val handler = MediaBrowseEventHandler(detector, config)

        val page = webPageProxy()
        val driver = webDriverProxy()
        val result = handler.invoke(page, driver)
        assertNull(result)
    }

    @Test
    @DisplayName("handler detects videos when enabled")
    fun testHandlerDetectsVideosWhenEnabled() = runBlocking {
        val expectedVideos = listOf(
            VideoDetector.VideoSource(
                tagName = "video",
                resolvedUrl = "https://example.com/video.mp4",
                width = 640, height = 360, hasControls = true,
            )
        )
        val detector = object : VideoDetector() {
            override suspend fun detect(driver: WebDriver) = expectedVideos
        }
        val handler = MediaBrowseEventHandler(detector, config)

        val page = webPageProxy()
        val driver = webDriverProxy()
        val result = handler.invoke(page, driver)
        assertNotNull(result)
        assertEquals(1, (result as List<*>).size)
    }

    @Test
    @DisplayName("handler returns empty list when no videos found")
    fun testHandlerNoVideosFound() = runBlocking {
        val detector = object : VideoDetector() {
            override suspend fun detect(driver: WebDriver) = emptyList<VideoDetector.VideoSource>()
        }
        val handler = MediaBrowseEventHandler(detector, config)

        val page = webPageProxy()
        val driver = webDriverProxy()
        val result = handler.invoke(page, driver)
        assertNotNull(result)
        assertTrue((result as List<*>).isEmpty())
    }

    @Test
    @DisplayName("handler returns null on detection exception")
    fun testHandlerReturnsNullOnException() = runBlocking {
        val detector = object : VideoDetector() {
            override suspend fun detect(driver: WebDriver): List<VideoDetector.VideoSource> {
                throw RuntimeException("CDP error")
            }
        }
        val handler = MediaBrowseEventHandler(detector, config)

        val page = webPageProxy()
        val driver = webDriverProxy()
        val result = handler.invoke(page, driver)
        assertNull(result)
    }

    @Test
    @DisplayName("config controls autoDetectEnabled")
    fun testConfigToggle() {
        assertTrue(MediaConfig(autoDetectEnabled = true).autoDetectEnabled)
        assertFalse(MediaConfig(autoDetectEnabled = false).autoDetectEnabled)
    }

    // ---- Minimal proxies for WebPage and WebDriver ----

    @Suppress("UNCHECKED_CAST")
    private fun webPageProxy(): WebPage {
        return Proxy.newProxyInstance(
            WebPage::class.java.classLoader,
            arrayOf(WebPage::class.java)
        ) { _, _, _ -> null } as WebPage
    }

    @Suppress("UNCHECKED_CAST")
    private fun webDriverProxy(): WebDriver {
        return Proxy.newProxyInstance(
            WebDriver::class.java.classLoader,
            arrayOf(WebDriver::class.java)
        ) { _, _, _ -> null } as WebDriver
    }
}
