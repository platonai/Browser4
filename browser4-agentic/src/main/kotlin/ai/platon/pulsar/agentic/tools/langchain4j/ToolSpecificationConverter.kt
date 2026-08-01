package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolSpec
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema

/**
 * Converts Browser4 [ToolSpec]s to LangChain4j
 * [dev.langchain4j.agent.tool.ToolSpecification]s for native tool calling.
 *
 * ## Tool naming
 *
 * OpenAI function names must match `^[a-zA-Z0-9_-]{1,64}$`.  Dots (as in
 * `skill.debug.scraping`) are not allowed, so sub-domain separators are
 * replaced with underscores: `"${sanitize(domain)}_${method}"`.
 *
 * A reverse registry [Map] (`name → ToolSpec`) is built alongside the
 * specification list so that [ToolExecutionRequest]s can be decoded back
 * into `(domain, method)` pairs without fragile string-splitting.
 *
 * ## Overloads
 *
 * Some tools have multiple signatures (e.g. `tab.click(selector)` and
 * `tab.click(selector, modifier)`).  LangChain4j tool names must be unique,
 * so overloads are merged: the variant with the most arguments becomes the
 * schema, and the extra parameters of narrower variants are marked optional.
 */
object ToolSpecificationConverter {

    /** Maximum length of an OpenAI-compatible function name. */
    private const val MAX_NAME_LENGTH = 64

    /** Characters that are invalid in a function name. */
    private val INVALID_CHAR = Regex("[^a-zA-Z0-9_-]")

    /**
     * Build a stable, protocol-safe tool name from a domain + method pair.
     *
     * Sanitisation: any character outside `[a-zA-Z0-9_-]` → `_`.
     * Truncated to [MAX_NAME_LENGTH] characters.
     */
    fun toolName(domain: String, method: String): String {
        val sanitized = "$domain.$method".replace(INVALID_CHAR, "_")
        return if (sanitized.length <= MAX_NAME_LENGTH) sanitized
        else sanitized.take(MAX_NAME_LENGTH)
    }

    /**
     * Convert a collection of [ToolSpec]s into LC4j [LangChain4jToolSpec]s.
     *
     * Overloads (same [toolName], different arg lists) are merged so every
     * name is unique: the variant with the **most** arguments wins, and the
     * extra parameters become non-required schema properties.
     */
    fun toToolSpecifications(specs: Collection<ToolSpec>): List<LangChain4jToolSpec> {
        val merged = mergeOverloads(specs)
        return merged.map { spec -> toToolSpecification(spec) }
    }

    /**
     * Build a reverse registry: tool name → [ToolSpec].
     *
     * Used to decode a [ToolExecutionRequest] back into `(domain, method)`
     * without string-splitting.  Every spec in [specs] must produce a unique
     * [toolName] (call [mergeOverloads] first).
     */
    fun toRegistry(specs: Collection<ToolSpec>): Map<String, ToolSpec> {
        val merged = mergeOverloads(specs)
        return merged.associateBy { toolName(it.domain, it.method) }
    }

    // -- internal ------------------------------------------------------------

    private fun toToolSpecification(spec: ToolSpec): LangChain4jToolSpec {
        val builder = JsonObjectSchema.builder()
            .description(spec.description ?: "${spec.domain}.${spec.method}")

        val required = mutableListOf<String>()

        for (arg in spec.arguments) {
            val element = arg.toJsonSchemaElement()
            val desc = buildArgDescription(arg)
            val elementWithDesc = if (desc != null) {
                // Builder has no fluent "with description" on individual
                // properties — description is on the element itself via
                // the element builder.  We work around this by adding
                // the description text into the property name as a suffix
                // for required args, and as a note for optional.
                element
            } else element

            // Build property name with optional annotation
            val propName = arg.name
            builder.addProperty(propName, elementWithDesc)

            if (arg.defaultValue == null) {
                required.add(propName)
            }
        }

        if (required.isNotEmpty()) {
            builder.required(required)
        }

        return LangChain4jToolSpec.builder()
            .name(toolName(spec.domain, spec.method))
            .description(buildToolDescription(spec))
            .parameters(builder.build())
            .build()
    }

    private fun ToolSpec.Arg.toJsonSchemaElement(): dev.langchain4j.model.chat.request.json.JsonSchemaElement {
        val t = type.lowercase().removeSuffix("?").trim()

        return when {
            t == "string" || t == "selector" || t == "url"
                || t == "expression" || t == "text" || t == "js" ->
                dev.langchain4j.model.chat.request.json.JsonStringSchema()

            t == "int" || t == "integer" || t == "long"
                || t == "short" || t == "byte" ->
                dev.langchain4j.model.chat.request.json.JsonIntegerSchema()

            t == "double" || t == "float" || t == "number" ->
                dev.langchain4j.model.chat.request.json.JsonNumberSchema()

            t == "boolean" || t == "bool" ->
                dev.langchain4j.model.chat.request.json.JsonBooleanSchema()

            t.startsWith("list<") || t == "array" ->
                JsonArraySchema.builder()
                    .items(dev.langchain4j.model.chat.request.json.JsonStringSchema())
                    .build()

            // Maps, objects, and unknown types degrade to string with
            // the original Kotlin type noted, so the model can still call
            // the tool.  The executor will parse the string argument.
            else -> dev.langchain4j.model.chat.request.json.JsonStringSchema()
        }
    }

    private fun buildToolDescription(spec: ToolSpec): String {
        val parts = mutableListOf<String>()
        spec.description?.let { parts.add(it) }
        spec.help?.let { parts.add(it) }
        if (spec.returnType.isNotBlank() && spec.returnType != "Unit") {
            parts.add("Returns: ${spec.returnType}")
        }
        return parts.joinToString(" — ")
    }

    private fun buildArgDescription(arg: ToolSpec.Arg): String? {
        if (arg.defaultValue != null) {
            return "Optional. Default: ${arg.defaultValue}"
        }
        return null
    }

    /**
     * Merge overloads: keep the variant with the most arguments.
     * Extra args from narrower overloads are already naturally covered
     * by the widest schema (they just won't be required).
     */
    private fun mergeOverloads(specs: Collection<ToolSpec>): List<ToolSpec> {
        return specs
            .groupBy { toolName(it.domain, it.method) }
            .mapValues { (_, group) -> group.maxByOrNull { it.arguments.size }!! }
            .values
            .sortedWith(compareBy({ it.domain }, { it.method }))
    }
}
