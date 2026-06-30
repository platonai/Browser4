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

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.media.config.MediaConfig
import ai.platon.pulsar.media.service.VideoDetector
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler

/**
 * Browse event handler that auto-detects videos when a page becomes steady.
 *
 * Registered on [BrowseEventHandlers.onDocumentSteady] — the optimal hook
 * because the DOM is fully rendered and stable. Detection operates entirely
 * via CDP JS evaluation, so [BlockRule] settings have no effect.
 */
open class MediaBrowseEventHandler(
    private val videoDetector: VideoDetector,
    private val config: MediaConfig,
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(MediaBrowseEventHandler::class)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        if (!config.autoDetectEnabled) return null

        return try {
            val videos = videoDetector.detect(driver)
            if (videos.isNotEmpty()) {
                logger.info(
                    "Auto-detected {} video(s) on page {}: {}",
                    videos.size,
                    driver.currentUrl(),
                    videos.map { v -> v.resolvedUrl ?: v.srcUrl ?: "(embedded)" }
                )
            }
            videos
        } catch (e: Exception) {
            logger.warn("Auto-detect videos error on {}: {}", driver.currentUrl(), e.message)
            null
        }
    }
}
