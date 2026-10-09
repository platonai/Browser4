package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatJson
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStep
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.exportStep
import ai.platon.pulsar.agentic.tools.advanced.format.readabilityStep
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument
import ai.platon.pulsar.skeleton.workflow.parse.html.HtmlToMarkdown
import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityResult

/**
 * Body and markup formats: the ones that describe the document itself.
 */

/**
 * `markdown` — the default format.
 *
 * With `onlyMainContent` the readable article is the better source, but a page
 * without an article (a listing, a dashboard, a search result) must still
 * produce markdown, so the full clean export is planned as a fallback and
 * carries the REQUIRED policy while the article read is DEGRADABLE. Both steps
 * read the same capture, so the fallback costs a store read, not a page load.
 */
internal object MarkdownFormatProvider : FormatProvider {

    override val id: String = PageFormats.MARKDOWN
    override val policy: StepPolicy = StepPolicy.REQUIRED

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> = buildList {
        if (options.onlyMainContent) {
            add(readabilityStep(id, StepPolicy.DEGRADABLE))
        }
        add(exportStep(id, clean = true, policy = StepPolicy.REQUIRED))
    }

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val article = results.firstOrNull { it.step.method == "readability" }
            ?.let { FormatJson.text(it.output, "content") }
            ?.takeIf { it.isNotBlank() }
        val export = results.firstOrNull { it.step.method == "export" }?.output?.takeIf { it.isNotBlank() }
        val html = article ?: export ?: return document

        val baseUrl = results.firstOrNull { it.step.method == "readability" }
            ?.let { FormatJson.text(it.output, "url") }
            .orEmpty()
            .ifBlank { ctx.snapshot?.href.orEmpty() }

        val markdown = HtmlToMarkdown().convert(html, baseUrl)
        return if (markdown.isBlank()) document else document.copy(markdown = markdown)
    }
}

/**
 * `html` — the cleaned document.
 *
 * Shares its export step with `markdown` when both are requested (same stage,
 * domain, method and arguments), so the second format costs nothing extra.
 */
internal object HtmlFormatProvider : FormatProvider {

    override val id: String = PageFormats.HTML
    override val policy: StepPolicy = StepPolicy.REQUIRED

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        listOf(exportStep(id, clean = true, policy = StepPolicy.REQUIRED))

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val html = results.firstOrNull { it.step.method == "export" }?.output ?: return document
        return if (html.isBlank()) document else document.copy(html = html)
    }
}

/**
 * `rawHtml` — the document exactly as fetched.
 *
 * Kept apart from [HtmlFormatProvider] by its `clean = false` argument, so a
 * request for both performs two exports of the same stored capture rather than
 * silently returning cleaned HTML for one of them.
 */
internal object RawHtmlFormatProvider : FormatProvider {

    override val id: String = PageFormats.RAW_HTML
    override val policy: StepPolicy = StepPolicy.REQUIRED

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        listOf(exportStep(id, clean = false, policy = StepPolicy.REQUIRED))

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val raw = results.firstOrNull { it.step.method == "export" }?.output ?: return document
        return if (raw.isBlank()) document else document.copy(rawHtml = raw)
    }
}

/**
 * `readability` — the Browser4 extension exposing the article extraction that
 * `markdown` uses internally, so a caller can read the article metadata
 * (byline, site name, excerpt, confidence) that markdown drops.
 */
internal object ReadabilityFormatProvider : FormatProvider {

    override val id: String = PageFormats.READABILITY
    override val policy: StepPolicy = StepPolicy.DEGRADABLE

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        listOf(readabilityStep(id, StepPolicy.DEGRADABLE))

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val output = results.firstOrNull { it.step.method == "readability" }?.output ?: return document
        val content = FormatJson.text(output, "content") ?: return document

        val readability = ReadabilityResult(
            title = FormatJson.text(output, "title").orEmpty(),
            byline = FormatJson.text(output, "byline").orEmpty(),
            siteName = FormatJson.text(output, "siteName").orEmpty(),
            excerpt = FormatJson.text(output, "excerpt").orEmpty(),
            content = content,
            textContent = FormatJson.text(output, "textContent").orEmpty(),
            length = FormatJson.int(output, "length") ?: 0,
            url = FormatJson.text(output, "url") ?: ctx.snapshot?.href.orEmpty(),
            confidence = FormatJson.double(output, "confidence") ?: 0.0,
        )
        return document.copy(readability = readability)
    }
}
