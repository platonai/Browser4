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
package ai.platon.pulsar.pptx.integration

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.pptx.config.PptxConfig
import ai.platon.pulsar.pptx.service.PageContentExtractor
import ai.platon.pulsar.pptx.service.PptxGenerator
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Browse event handler that auto-generates PPTX when a page becomes steady.
 *
 * Registered on [BrowseEventHandlers.onDocumentSteady] — the optimal hook
 * because the DOM is fully rendered and stable.
 *
 * Auto-generation is disabled by default ([PptxConfig.autoGenerateEnabled] = false).
 * Enable with `pptx.auto-generate.enabled=true`.
 */
open class PptxBrowseEventHandler(
    private val contentExtractor: PageContentExtractor,
    private val pptxGenerator: PptxGenerator,
    private val config: PptxConfig,
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(PptxBrowseEventHandler::class)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        if (!config.autoGenerateEnabled) return null

        return try {
            val blocks = contentExtractor.extract(driver)
            if (blocks.isEmpty()) {
                logger.debug("No content blocks extracted from {}, skipping PPTX auto-generate", driver.currentUrl())
                return null
            }

            val pageTitle = driver.evaluate("document.title").toString()
            val pageUrl = driver.currentUrl()

            logger.info(
                "Auto-generating PPTX for '{}' ({} blocks extracted)",
                pageTitle, blocks.size
            )

            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch {
                try {
                    val outputPath = pptxGenerator.generate(
                        blocks = blocks,
                        pageUrl = pageUrl,
                        pageTitle = pageTitle,
                        outputDir = Path.of(config.outputDir)
                    )
                    logger.info("Auto-generated PPTX for {}: {}", pageUrl, outputPath)
                } catch (e: Exception) {
                    logger.warn("Auto-generate PPTX error on {}: {}", pageUrl, e.message)
                }
            }

            mapOf(
                "status" to "generating",
                "blockCount" to blocks.size,
                "pageTitle" to pageTitle.take(100),
                "pageUrl" to pageUrl,
            )
        } catch (e: Exception) {
            logger.warn("Auto-generate PPTX error on {}: {}", driver.currentUrl(), e.message)
            null
        }
    }
}
