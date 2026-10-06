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
     * A session is **required** (decision A2: "open first"). Every step reads the page a
     * session is already on, so a request that names none has no honest answer: the
     * alternatives are to fail or to pick a session on the caller's behalf, and picking
     * one reads *another caller's page* — the same class of silent wrong answer that
     * removing `url` was meant to end. The refusal names `open` because that is the fix.
     *
     * @param formats the requested formats, already normalized and validated.
     * @param sessionId the addressed session; null or blank is refused.
     * @param onlyMainContent whether markdown should come from the readable article
     *   rather than the whole cleaned page.
     * @return one document carrying every format that was delivered.
     * @throws IllegalArgumentException when no session is addressed — before a runner
     *   is built, so a refused request never touches a browser.
     * @throws Exception the original failure of a REQUIRED step.
     */
    suspend fun scrape(
        formats: List<PageFormat>,
        sessionId: String? = null,
        onlyMainContent: Boolean = true,
    ): ScrapedDocument {
        // Resolved explicitly rather than via `require`, so the non-blank session is a
        // `String` by construction and the check cannot be read as re-validating a
        // value that is already known good.
        val session = sessionId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(NO_SESSION_MESSAGE)

        return PageFormatEngine(runnerFactory.create(session)).scrape(
            PageScrapeRequest(
                formats = formats,
                onlyMainContent = onlyMainContent,
            )
        )
    }

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

    companion object {
        /**
         * Why a session is required, in the caller's terms.
         *
         * A constant rather than an inline literal because the wording is part of the
         * contract: both faces (REST and the `page` tool domain) surface this same
         * sentence, and the tests assert it names both the argument and the fix.
         */
        const val NO_SESSION_MESSAGE: String =
            "'sessionId' is required: every step reads the page a session is already on, so this " +
                "request cannot choose one for you — guessing would read a different session's page. " +
                "Open the page first (`open <url>`), then scrape it with that session's id."
    }
}
