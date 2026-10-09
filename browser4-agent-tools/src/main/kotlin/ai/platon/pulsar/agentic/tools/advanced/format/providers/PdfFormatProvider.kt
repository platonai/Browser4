package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.ArtifactSpec
import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStage
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStep
import ai.platon.pulsar.agentic.tools.advanced.format.LIVE_TAB_DOMAIN
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.UnsupportedFormatOptionException
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument

/** The extension a rendered PDF is written with. */
private const val PDF_EXTENSION = "pdf"

/**
 * `pdf` — the second format that needs the live tab, and the first to reuse the
 * artifact path `screenshot` opened.
 *
 * ## `pdf` is a Browser4 extension, not a Firecrawl format
 *
 * Worth stating because it decides how options are treated. Firecrawl's own `pdf`
 * code (`apps/api/src/scraper/scrapeURL/engines/pdf/`) handles the opposite
 * direction: it **parses** a document that *is* a PDF into markdown, and exposes no
 * page-to-PDF output format at all. So there is no Firecrawl option set to be
 * compatible with here, and the only options that can arrive are the flat superset
 * carried by [PageFormat].
 *
 * ## What the tool can do, and why that makes the options refusals
 *
 * `tab.pdf` prints the page through CDP `Page.printToPDF` — A4, portrait, background
 * graphics on — and returns the document as **base64**. It takes **no arguments**:
 * its executor validates against an empty allowed set, so anything passed would be
 * rejected as extraneous. That leaves exactly one honest treatment for an option we
 * can detect: refuse it, naming the option. Silently ignoring a request the caller
 * spelled out is the failure this layer exists to avoid.
 *
 * - `viewport` → refused. `Page.printToPDF` has no region; a PDF is the document.
 * - `quality` → refused. There is no such knob on the PDF path.
 * - `fullPage` → **not** refused, which is a deliberate asymmetry. [PageFormat.fullPage]
 *   is a non-null `Boolean` defaulting to `false`, so "the caller did not mention it"
 *   and "the caller asked for `false`" are the same value; refusing `false` would
 *   refuse every plain `"pdf"` request. `true` is simply the truth about a PDF — the
 *   whole document, not a viewport.
 * - `base64` → honoured, not an option of the tool but of this layer: the file is
 *   written either way, so the path is always present and the bytes are what the
 *   caller opts into on top (same policy as `screenshot`).
 *
 * The format is **DEGRADABLE**: an engine that cannot print (or a headless host with
 * no tab at all) must not fail a request that also asked for markdown.
 */
object PdfFormatProvider : FormatProvider {

    override val id: String = PageFormats.PDF

    override val policy: StepPolicy = StepPolicy.DEGRADABLE

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> {
        format.viewport?.let {
            throw UnsupportedFormatOptionException(
                "viewport is not supported — a PDF is printed through CDP `Page.printToPDF`, which " +
                    "renders the whole document and has no region to capture"
            )
        }
        format.quality?.let {
            throw UnsupportedFormatOptionException(
                "quality is not supported — the PDF path has no quality setting (format is fixed at " +
                    "A4 portrait with backgrounds printed)"
            )
        }

        return listOf(
            FormatStep(
                format = id,
                stage = FormatStage.LIVE_TAB,
                domain = LIVE_TAB_DOMAIN,
                method = "pdf",
                // Empty on purpose: `tab.pdf` validates against an empty allowed set,
                // so any argument here would be refused as extraneous rather than used.
                args = emptyMap(),
                policy = policy,
                artifact = ArtifactSpec(extension = PDF_EXTENSION, nameHint = id),
            )
        )
    }

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val result = results.firstOrNull() ?: return document
        // `output` is the path the host wrote to; `raw` is the base64 it wrote.
        val withPath = document.copy(pdf = result.output.takeIf { it.isNotBlank() })
        if (!format.base64) return withPath
        return withPath.copy(pdfBase64 = result.raw.takeIf { it.isNotBlank() })
    }
}
