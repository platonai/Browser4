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
package ai.platon.pulsar.images.integration

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.images.config.ImageConfig
import ai.platon.pulsar.images.service.ImageDetector
import ai.platon.pulsar.images.service.ImageDownloader
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Browse event handler that auto-detects images when a page becomes steady.
 *
 * Registered on [BrowseEventHandlers.onDocumentSteady] — the optimal hook
 * because the DOM is fully rendered and stable. Detection operates entirely
 * via CDP JS evaluation, so [BlockRule] settings have no effect.
 *
 * When [ImageConfig.autoDownloadEnabled] is also true, detected images are
 * downloaded automatically in the background.
 */
open class ImageBrowseEventHandler(
    private val imageDetector: ImageDetector,
    private val imageDownloader: ImageDownloader,
    private val config: ImageConfig,
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(ImageBrowseEventHandler::class)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        if (!config.autoDetectEnabled) return null

        return try {
            val images = imageDetector.detect(driver)
            if (images.isNotEmpty()) {
                val urls = images.mapNotNull { it.resolvedUrl ?: it.srcUrl }
                logger.info(
                    "Auto-detected {} image(s) on page {}: {}",
                    images.size,
                    driver.currentUrl(),
                    urls.joinToString(", ") { it.take(100) }
                )

                // Optionally auto-download in the background (fire-and-forget)
                if (config.autoDownloadEnabled) {
                    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                    GlobalScope.launch {
                        try {
                            val summary = imageDownloader.downloadAll(
                                images, java.nio.file.Path.of(config.downloadDir)
                            )
                            logger.info(
                                "Auto-download complete for {}: {}/{} succeeded",
                                driver.currentUrl(),
                                summary.successful,
                                summary.totalAttempted
                            )
                        } catch (e: Exception) {
                            logger.warn("Auto-download error on {}: {}", driver.currentUrl(), e.message)
                        }
                    }
                }
            }
            images
        } catch (e: Exception) {
            logger.warn("Auto-detect images error on {}: {}", driver.currentUrl(), e.message)
            null
        }
    }
}
