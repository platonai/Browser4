package ai.platon.pulsar.skeleton.workflow.parse.html

import ai.platon.pulsar.dom.FeaturedDocument

/**
 * Input for a [PageSummaryAlgorithm].
 *
 * @property document  The parsed DOM document of the page to summarize. The
 *   document represents a FRESH snapshot of the active tab; algorithms must
 *   not mutate it (the built-in WPSI algorithm works on a clone).
 * @property pageUrl   The normalized page URL.
 * @property title     The page title (from [FeaturedDocument.title]).
 */
data class PageSummaryInput(
    val document: FeaturedDocument,
    val pageUrl: String,
    val title: String,
)
