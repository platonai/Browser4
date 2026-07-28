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
package ai.platon.pulsar.markdown.integration

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.markdown.config.MarkdownConfig
import ai.platon.pulsar.markdown.service.SiteCrawler
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Browse event handler that auto-crawls a site when a page becomes steady.
 *
 * Registered on [BrowseEventHandlers.onDocumentSteady] — the optimal hook
 * because the DOM is fully rendered and stable.
 *
 * Auto-crawl is disabled by default ([MarkdownConfig.autoCrawlEnabled] = false).
 * Enable with `markdown.auto-crawl.enabled=true`.
 *
 * When enabled, this fires a background crawl starting from the current page.
 * The crawl follows internal links within the configured depth and page limits.
 */
open class MarkdownBrowseEventHandler(
    private val config: MarkdownConfig,
    private val siteCrawler: SiteCrawler,
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(MarkdownBrowseEventHandler::class)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        if (!config.autoCrawlEnabled) return null

        return try {
            val pageUrl = driver.currentUrl()
            logger.info(
                "Auto-crawling site starting from {} (maxDepth={}, maxPages={})",
                pageUrl, config.maxDepth, config.maxPages
            )

            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch {
                try {
                    val summary = siteCrawler.crawl(driver = driver, startUrl = pageUrl)
                    logger.info(
                        "Auto-crawl complete for {}: {} pages, {} failed, {} links",
                        pageUrl, summary.pagesCrawled, summary.pagesFailed, summary.totalLinksDiscovered
                    )
                } catch (e: Exception) {
                    logger.warn("Auto-crawl error on {}: {}", pageUrl, e.message)
                }
            }

            mapOf(
                "status" to "crawling",
                "startUrl" to pageUrl,
            )
        } catch (e: Exception) {
            logger.warn("Auto-crawl error on {}: {}", driver.currentUrl(), e.message)
            null
        }
    }
}
