package ai.platon.pulsar.linkstats.integration

import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.linkstats.config.LinkstatsConfig
import ai.platon.pulsar.linkstats.service.LinkSummary
import ai.platon.pulsar.linkstats.service.LinkstatsService
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler
import org.slf4j.LoggerFactory

/**
 * Browse event handler that summarizes the page link distribution when the
 * document becomes steady.
 *
 * Registered on [ai.platon.pulsar.skeleton.event.BrowseEventHandlers.onDocumentSteady]
 * — the DOM is fully rendered and stable at that point. The handler only logs:
 * it performs no blocking I/O, and every failure is caught and logged.
 */
open class LinkstatsBrowseEventHandler(
    private val service: LinkstatsService,
    private val config: LinkstatsConfig,
) : WebPageWebDriverEventHandler() {
    private val logger = LoggerFactory.getLogger(LinkstatsBrowseEventHandler::class.java)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        return try {
            val summary = LinkSummary.from(service.summarizeAsMap(driver))
            if (summary.total >= config.minLinks) {
                logger.info(
                    "Page {} link summary: {} total / {} internal / {} external",
                    driver.currentUrl(),
                    summary.total,
                    summary.internal,
                    summary.external,
                )
            }
            summary
        } catch (e: Exception) {
            logger.warn("Linkstats browse handler error on {}: {}", safeCurrentUrl(driver), e.message)
            null
        }
    }

    private suspend fun safeCurrentUrl(driver: WebDriver): String {
        return try {
            driver.currentUrl()
        } catch (e: Exception) {
            "<unknown>"
        }
    }
}