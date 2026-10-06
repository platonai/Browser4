package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument
import com.fasterxml.jackson.databind.JsonNode

/**
 * The tool domain the snapshot family lives in.
 *
 * Public because it is a wire name a host has to spell: the REST-side
 * [ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner] implementation
 * dispatches to it, and a second literal there could drift from this one.
 */
const val HTML_SNAPSHOT_DOMAIN = "html_snapshot"

/**
 * Turns one requested format into tool steps and back into document fields.
 *
 * A provider is the whole knowledge about a format in one place: what has to be
 * read, and how the raw tool output becomes the field the caller sees. Providers
 * are stateless objects, so planning the same request twice yields the same plan
 * and the same output.
 *
 * Providers run in the order the formats were requested, and only see the
 * results of the steps they declared themselves.
 */
interface FormatProvider {

    /** The canonical format id this provider implements. */
    val id: String

    /**
     * What a failed step means for the request.
     *
     * A format the caller asked for by name is [StepPolicy.REQUIRED]; a
     * best-effort enrichment is [StepPolicy.DEGRADABLE] and degrades into a
     * `warning`.
     */
    val policy: StepPolicy

    /**
     * The steps this format needs, in execution order.
     *
     * May return several steps for one format: `markdown` reads the readable
     * article first and declares the full export as a fallback, so a page
     * without an article still yields markdown.
     */
    fun steps(format: PageFormat, options: FormatOptions): List<FormatStep>

    /**
     * Write this format's fields into [document].
     *
     * @param results the outputs of the steps from [steps] that ran and
     *   succeeded — may be a subset (a degraded step produces no result).
     * @return the document with this format's fields set; return it unchanged
     *   when no result carries usable output.
     */
    fun assemble(
        format: PageFormat,
        results: List<StepResult>,
        ctx: AssemblyContext,
        document: ScrapedDocument,
    ): ScrapedDocument
}

/** A snapshot read of the page's HTML. */
internal fun exportStep(format: String, clean: Boolean, policy: StepPolicy): FormatStep = FormatStep(
    format = format,
    stage = FormatStage.FROM_SNAPSHOT,
    domain = HTML_SNAPSHOT_DOMAIN,
    method = "export",
    args = mapOf("clean" to clean),
    policy = policy,
)

/** A snapshot read of the readable article extraction. */
internal fun readabilityStep(format: String, policy: StepPolicy): FormatStep = FormatStep(
    format = format,
    stage = FormatStage.FROM_SNAPSHOT,
    domain = HTML_SNAPSHOT_DOMAIN,
    method = "readability",
    args = emptyMap(),
    policy = policy,
)

/** A snapshot read of one field across all matching elements. */
internal fun scrapeAllStep(
    format: String,
    field: String,
    selector: String,
    attrName: String? = null,
    absoluteUrls: Boolean = false,
    policy: StepPolicy = StepPolicy.DEGRADABLE,
): FormatStep = FormatStep(
    format = format,
    stage = FormatStage.FROM_SNAPSHOT,
    domain = HTML_SNAPSHOT_DOMAIN,
    method = "scrape_all",
    args = buildMap {
        put("field", field)
        put("selector", selector)
        if (attrName != null) put("attrName", attrName)
        put("absoluteUrls", absoluteUrls)
    },
    policy = policy,
)

/**
 * Tolerant readers for the JSON the snapshot tools return.
 *
 * The tools speak JSON over the tool boundary, so a provider parses rather than
 * casting. Every reader returns a null/empty result for a shape it does not
 * recognize instead of throwing: a malformed payload is a degraded format, not a
 * failed request.
 */
internal object FormatJson {

    private val mapper by lazy { pulsarObjectMapper() }

    /** Parse [json] into a tree, or null when it is not JSON. */
    fun tree(json: String): JsonNode? = runCatching { mapper.readTree(json) }.getOrNull()

    /** The text of [field], or null when absent, null or blank. */
    fun text(json: String, field: String): String? =
        tree(json)?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    /** The integer value of [field], or null when absent or not a number. */
    fun int(json: String, field: String): Int? =
        tree(json)?.get(field)?.takeIf { it.isNumber }?.asInt()

    /** The double value of [field], or null when absent or not a number. */
    fun double(json: String, field: String): Double? =
        tree(json)?.get(field)?.takeIf { it.isNumber }?.asDouble()

    /** A JSON array of strings, skipping non-textual entries. */
    fun stringList(json: String): List<String> {
        val node = tree(json) ?: return emptyList()
        if (!node.isArray) return emptyList()
        return node.mapNotNull { element -> element.takeIf { it.isTextual }?.asText() }
    }

    /** A field of the object as `List<Map<String, Any?>>`, or null when absent. */
    fun objectList(json: String, field: String): List<Map<String, Any?>>? {
        val node = tree(json)?.get(field) ?: return null
        if (!node.isArray) return null
        return node.mapNotNull { element ->
            if (!element.isObject) null else toRow(element)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toRow(node: JsonNode): Map<String, Any?>? =
        mapper.convertValue(node, Map::class.java) as? Map<String, Any?>
}
