package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.browser4.common.Beta
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner

/**
 * How a `page.scrape` request obtains the host bridge the format engine runs on.
 *
 * A factory rather than a runner instance because the bridge is **per request**:
 * it is bound to the session the request addresses, and a shared instance would
 * let one caller's formats read another caller's page.
 *
 * This is also the seam that keeps [PageScrapeService] testable — a test injects a
 * factory returning a runner over a recording dispatcher, so the service's argument
 * handling and response shaping can be exercised without a browser.
 */
@Beta
fun interface FormatStepRunnerFactory {

    /**
     * Build the bridge for one request.
     *
     * @param sessionId the addressed session, or null when the caller did not name
     *   one; the production implementation then relies on the dispatch layer's
     *   session resolution, which fails loudly rather than picking a page.
     */
    fun create(sessionId: String?): FormatStepRunner
}
