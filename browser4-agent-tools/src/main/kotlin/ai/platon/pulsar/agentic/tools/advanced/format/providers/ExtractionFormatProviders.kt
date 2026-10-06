package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatJson
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatProvider
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStage
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStep
import ai.platon.pulsar.agentic.tools.advanced.format.HTML_SNAPSHOT_DOMAIN
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.scrapeAllStep
import ai.platon.pulsar.skeleton.workflow.format.AttributeValue
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument

/**
 * Extraction formats: the ones that read specific values out of the document.
 */

/**
 * `links` — every `href` on the page, resolved to an absolute URL.
 *
 * `absoluteUrls = true` because a relative href is not a link a caller can
 * follow; the raw value is available through `attributes` when that is what is
 * wanted.
 */
internal object LinksFormatProvider : FormatProvider {

    override val id: String = PageFormats.LINKS
    override val policy: StepPolicy = StepPolicy.DEGRADABLE

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        listOf(scrapeAllStep(id, field = "attr", selector = "a", attrName = "href", absoluteUrls = true))

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val output = results.firstOrNull()?.output ?: return document
        val links = FormatJson.stringList(output)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return document.copy(links = links)
    }
}

/**
 * `images` — every `src` on the page, resolved to an absolute URL.
 *
 * Data URIs are kept: unlike markdown (where an inline data image is noise),
 * the `images` format exists to enumerate what the page embeds.
 */
internal object ImagesFormatProvider : FormatProvider {

    override val id: String = PageFormats.IMAGES
    override val policy: StepPolicy = StepPolicy.DEGRADABLE

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        listOf(scrapeAllStep(id, field = "attr", selector = "img", attrName = "src", absoluteUrls = true))

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val output = results.firstOrNull()?.output ?: return document
        val images = FormatJson.stringList(output)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return document.copy(images = images)
    }
}

/**
 * `attributes` — caller-chosen `{selector, attribute}` pairs.
 *
 * One read per pair, correlated by the pair itself rather than by position, so a
 * dropped read cannot silently shift values onto the wrong selector. Attribute
 * values are returned verbatim (`absoluteUrls = false`): the whole point of this
 * format is to see what the document actually says.
 */
internal object AttributesFormatProvider : FormatProvider {

    override val id: String = PageFormats.ATTRIBUTES
    override val policy: StepPolicy = StepPolicy.REQUIRED

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> =
        format.selectors.map { selector ->
            scrapeAllStep(
                format = id,
                field = "attr",
                selector = selector.selector,
                attrName = selector.attribute,
                absoluteUrls = false,
                policy = StepPolicy.REQUIRED,
            )
        }

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val attributes = format.selectors.map { selector ->
            val output = results.firstOrNull { result ->
                result.step.args["selector"] == selector.selector &&
                    result.step.args["attrName"] == selector.attribute
            }?.output
            AttributeValue(
                selector = selector.selector,
                attribute = selector.attribute,
                values = output?.let { FormatJson.stringList(it) }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
        return document.copy(attributes = attributes)
    }
}

/**
 * `deterministicJson` — extraction that does not call a model.
 *
 * In Browser4 the deterministic extractor is X-SQL, so the caller supplies the
 * statement (`{"type":"deterministicJson","sql":"select ..."}`) instead of a
 * JSON schema. That is a deliberate difference from Firecrawl, where this format
 * takes a schema: a schema cannot be compiled into X-SQL without guessing what
 * the caller meant, and guessing is exactly what a *deterministic* format must
 * not do. A schema-only request is rejected during validation with a pointer at
 * `json`, which is the schema-driven (LLM) format.
 *
 * The X-SQL engine reports failures in the response body rather than by
 * throwing, so a missing result set is turned into an error here — a silent
 * empty `json` field would look like "the page has no such data".
 */
internal object DeterministicJsonFormatProvider : FormatProvider {

    override val id: String = PageFormats.DETERMINISTIC_JSON
    override val policy: StepPolicy = StepPolicy.REQUIRED

    override fun steps(format: PageFormat, options: FormatOptions): List<FormatStep> {
        val sql = format.sql?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            FormatStep(
                format = id,
                stage = FormatStage.FROM_SNAPSHOT,
                domain = HTML_SNAPSHOT_DOMAIN,
                method = "query",
                args = mapOf("sql" to sql),
                policy = StepPolicy.REQUIRED,
            )
        )
    }

    override fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument {
        val output = results.firstOrNull { it.step.method == "query" }?.output ?: return document
        val rows = FormatJson.objectList(output, "resultSet")
            ?: throw IllegalStateException(
                FormatJson.text(output, "message")
                    ?: "X-SQL query returned no result set for the deterministicJson format"
            )
        return document.copy(json = rows)
    }
}
