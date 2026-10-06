package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.tools.advanced.format.FormatProviders
import ai.platon.pulsar.agentic.tools.advanced.format.PageFormatEngine
import ai.platon.pulsar.agentic.tools.advanced.format.PageFormatPlanBuilder
import ai.platon.pulsar.agentic.tools.advanced.format.PageScrapeRequest
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributor
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributorRegistry
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument

/**
 * Runs one `page_scrape` request: parse → plan → execute → assemble.
 *
 * The service owns the request-level decisions and nothing else — the format engine
 * owns capture-once, staging and per-format degradation, and the runner factory owns
 * where the tools live. Keeping those apart is what lets this class be tested with a
 * fake runner.
 *
 * @property runnerFactory builds the host bridge for each request, bound to the
 *   session the caller addressed.
 */
class PageScrapeService(private val runnerFactory: FormatStepRunnerFactory) {

    /**
     * Produce the document for one request.
     *
     * There is no `url` parameter: a request-level URL used to be forwarded, recorded
     * in `metadata.sourceURL` and otherwise ignored — the steps read the session's
     * *active* page, and only `readability`/`query` accept a URL at all (`export`
     * hardcodes the active page). That produced a plausible document about the wrong
     * page. Targeting another page needs the design's Stage 0 (ENSURE: load it
     * read-only on the shared scrape session, then read that), and the parameter
     * comes back with it; until then `open <url>` first is the only honest way.
     *
     * @param formats the requested formats, already normalized and validated.
     * @param sessionId the addressed session, or null when the caller named none.
     * @param onlyMainContent whether markdown should come from the readable article
     *   rather than the whole cleaned page.
     * @return one document carrying every format that was delivered.
     * @throws Exception the original failure of a REQUIRED step.
     */
    suspend fun scrape(
        formats: List<PageFormat>,
        sessionId: String? = null,
        onlyMainContent: Boolean = true,
    ): ScrapedDocument = PageFormatEngine(runnerFactory.create(sessionId)).scrape(
        PageScrapeRequest(
            formats = formats,
            onlyMainContent = onlyMainContent,
        )
    )

    /**
     * What this deployment can deliver, for the capability listing.
     *
     * `available` is a **configuration** answer, not a promise: a registered
     * contributor whose service is up now can still fail at call time, and that
     * failure is reported per request through the document's `warning`. Saying so
     * here as well would mean two sources of truth for the same fact.
     *
     * @return one entry per accepted format id, in [PageFormats.ALL] order.
     */
    fun formats(): List<Map<String, Any?>> = PageFormats.ALL.map { id ->
        linkedMapOf<String, Any?>(
            "id" to id,
            "available" to isAvailable(id),
            "reason" to unavailableReason(id),
            "source" to sourceOf(id),
            "requires" to requiresOf(id),
        )
    }

    private fun isAvailable(id: String): Boolean = when {
        FormatProviders.isImplemented(id) -> true
        PageFormats.isContributed(id) -> PageFormatContributorRegistry.instance.get(id)?.isAvailable() == true
        else -> false
    }

    private fun unavailableReason(id: String): String? = when {
        FormatProviders.isImplemented(id) -> null
        PageFormats.isContributed(id) -> {
            val contributor = PageFormatContributorRegistry.instance.get(id)
            when {
                contributor == null -> "no plugin contributor installed"
                contributor.isAvailable() -> null
                else -> contributor.unavailableReason()?.takeIf { it.isNotBlank() }
                    ?: "the contributor reports itself unavailable"
            }
        }

        PageFormats.isDeprecated(id) -> "deprecated; use ${deprecationReplacement(id)}"
        else -> PageFormatPlanBuilder.unavailableWarning(id).substringAfter(": ")
    }

    private fun sourceOf(id: String): String = when {
        PageFormats.isContributed(id) -> "plugin"
        else -> "core"
    }

    /** The inputs a contributor declares; empty for a core format. */
    private fun requiresOf(id: String): List<String> {
        val contributor: PageFormatContributor = PageFormatContributorRegistry.instance.get(id) ?: return emptyList()
        return contributor.requires.map { it.name }.sorted()
    }

    private fun deprecationReplacement(id: String): String =
        if (id == PageFormats.QUERY) "question or highlights" else "its replacement"
}
