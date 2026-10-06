package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.agentic.tools.advanced.format.providers.AttributesFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.DeterministicJsonFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.HtmlFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.ImagesFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.LinksFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.MarkdownFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.PdfFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.RawHtmlFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.ReadabilityFormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.providers.ScreenshotFormatProvider

/**
 * The formats this build can deliver, keyed by canonical format id.
 *
 * The registry is the single place that answers "is this format implemented?".
 * A format that is a *valid* id but has no provider here (a contributor-provided
 * format with no plugin installed, or a core format scheduled for a later phase)
 * is reported as unavailable and degrades into a `warning` — it never fails the
 * request, and it never silently disappears.
 *
 * Adding a format means adding one provider and one entry here; the plan
 * builder, the engine and the capability listing all pick it up.
 */
object FormatProviders {

    private val providers: Map<String, FormatProvider> = listOf(
        MarkdownFormatProvider,
        HtmlFormatProvider,
        RawHtmlFormatProvider,
        ReadabilityFormatProvider,
        LinksFormatProvider,
        ImagesFormatProvider,
        AttributesFormatProvider,
        DeterministicJsonFormatProvider,
        // The LIVE_TAB providers: everything above answers from the capture alone.
        ScreenshotFormatProvider,
        PdfFormatProvider,
    ).associateBy { it.id }

    /**
     * The provider for [id], or null when this build cannot deliver it.
     *
     * @param id a canonical format id (see `PageFormats`).
     */
    fun find(id: String): FormatProvider? = providers[id]

    /** True when this build can deliver [id]. */
    fun isImplemented(id: String): Boolean = providers.containsKey(id)

    /** Every implemented format id. */
    fun implemented(): Set<String> = providers.keys

    /** Every provider, in registration order. */
    fun list(): List<FormatProvider> = providers.values.toList()
}
