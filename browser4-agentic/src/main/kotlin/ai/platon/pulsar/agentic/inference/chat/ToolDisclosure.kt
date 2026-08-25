package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.data.message.ToolExecutionResultMessage

/**
 * Progressive tool disclosure for the native tool-calling loop
 * (design: docs-dev/copilot/browser4-agent-tool-disclosure-feedback-design.md §1.2,
 * TOOL_CALLING-mode landing).
 *
 * The problem: the loop used to expose the FULL tool registry (coding ×51,
 * b4, shell, fs, custom plugins, ...) as native [LangChain4jToolSpec]s in
 * EVERY request — tens of thousands of tokens of JSON schemas per round, most
 * of it irrelevant to the task at hand.
 *
 * The fix is a curated **initial set** plus two tiny meta tools that let the
 * model pull in exactly what it needs:
 * - `system.listTools(domain?)` — list tools that are NOT yet exposed;
 * - `system.exposeTools(toolNames[])` — enable tools for the rest of the task.
 *
 * Both are intercepted by [AgentToolCallLoop] before the coordinator: the loop
 * holds the mutable exposed set, so the very next request carries the expanded
 * specifications. The meta tools cost ~200 tokens and are always exposed.
 *
 * NOTE: tool names in the wild are the converter's SANITIZED form
 * (`system_listTools`, `coding_read` — the dot becomes an underscore), so all
 * name matching here works on that form.
 *
 * Pure logic — no IO — so it is unit-testable without a model or browser.
 */
object ToolDisclosureTools {

    /** Sanitized meta tool names (converter replaces `.` with `_`). */
    val LIST_TOOLS_NAME: String = ToolSpecificationConverter.toolName("system", "listTools")
    val EXPOSE_TOOLS_NAME: String = ToolSpecificationConverter.toolName("system", "exposeTools")

    val META_NAMES: Set<String> = setOf(LIST_TOOLS_NAME, EXPOSE_TOOLS_NAME)

    /** Default core: whole page-interaction + agent + system domains. */
    val DEFAULT_CORE_DOMAINS: Set<String> = setOf("tab", "browser", "agent", "system")

    /**
     * The two meta tool specifications, converted through the standard
     * converter so their JSON schemas match every other tool.
     */
    fun metaSpecs(): List<LangChain4jToolSpec> = ToolSpecificationConverter.toToolSpecifications(
        listOf(
            ToolSpec(
                domain = "system",
                method = "listTools",
                arguments = listOf(ToolSpec.Arg("domain", "String", "")),
                returnType = "String",
                description = "List tools that are NOT yet exposed to you, so you can discover the full tool set " +
                    "of this agent (browser, coding, shell, plugin tools...). Optional 'domain' filters the " +
                    "listing, e.g. \"coding\". After listing, call system.exposeTools with the names you need.",
            ),
            ToolSpec(
                domain = "system",
                method = "exposeTools",
                // "list<...>" maps to a JSON array schema (see
                // ToolSpecificationConverter.toJsonSchemaElement); the
                // parser accepts arrays and single strings either way.
                arguments = listOf(ToolSpec.Arg("toolNames", "list<String>")),
                returnType = "String",
                description = "Expose additional tools for the rest of this task. toolNames accepts fully " +
                    "qualified names (\"coding_read\") or bare method names (\"read\"). Returns the enabled " +
                    "tools; unknown or ambiguous names are skipped.",
            ),
        )
    )

    /**
     * Select the INITIAL tool set exposed to the model. Works on [ToolSpec]s
     * (domain/method are intact there — sanitized LC4j names lose the split).
     *
     * @param mode `all` → everything (legacy behavior); `core` → whole
     *   [coreDomains] plus [coreMethodAllowlist] methods of other domains;
     *   anything else → comma-separated pattern list, where each token is
     *   `domain.*` (whole domain), `domain.method` (exact), bare `method`
     *   (any domain), or the special tokens `all` / `core` (compose with
     *   explicit patterns, e.g. `core,coding.ktSymbols` keeps the core set
     *   AND the named long-tail tool). A pattern list that matches nothing
     *   yields an empty list — callers decide whether to fall back.
     */
    fun selectInitialSpecs(
        specs: Collection<ToolSpec>,
        mode: String,
        coreDomains: Set<String> = DEFAULT_CORE_DOMAINS,
        coreMethodAllowlist: Map<String, Set<String>> = emptyMap(),
    ): List<ToolSpec> {
        if (mode.equals("all", ignoreCase = true)) return specs.toList()
        if (mode.isBlank() || mode.equals("core", ignoreCase = true)) {
            return specs.filter { isCoreSpec(it, coreDomains, coreMethodAllowlist) }
        }
        val tokens = mode.split(',').map { it.trim() }.filter { it.isNotBlank() }
        // `all` composes as a token too: anything else in the list is then moot.
        if (tokens.any { it.equals("all", ignoreCase = true) }) return specs.toList()
        val includeCore = tokens.any { it.equals("core", ignoreCase = true) }
        val patterns = tokens.filterNot { it.equals("core", ignoreCase = true) || it.equals("all", ignoreCase = true) }
        return specs.filter { spec ->
            (includeCore && isCoreSpec(spec, coreDomains, coreMethodAllowlist)) ||
                patterns.any { pattern -> matchesPattern(spec, pattern) }
        }
    }

    private fun isCoreSpec(
        spec: ToolSpec,
        coreDomains: Set<String>,
        coreMethodAllowlist: Map<String, Set<String>>,
    ): Boolean =
        spec.domain in coreDomains || coreMethodAllowlist[spec.domain]?.contains(spec.method) == true

    private fun matchesPattern(spec: ToolSpec, pattern: String): Boolean = when {
        pattern == "*" -> true
        pattern.endsWith(".*") -> spec.domain == pattern.removeSuffix(".*")
        pattern.contains('.') -> spec.domain == pattern.substringBefore('.') &&
            spec.method == pattern.substringAfter('.')
        else -> spec.method == pattern
    }

    /** Parse a single string argument (e.g. listTools' `domain`) from the arguments JSON. */
    fun parseStringArg(argumentsJson: String?, key: String): String? {
        if (argumentsJson.isNullOrBlank()) return null
        return try {
            val node = pulsarObjectMapper().readTree(argumentsJson).get(key)
            node?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Parse `toolNames` from the arguments JSON: an array of strings or a single string. */
    fun parseToolNames(argumentsJson: String?): List<String> {
        if (argumentsJson.isNullOrBlank()) return emptyList()
        return try {
            val node = pulsarObjectMapper().readTree(argumentsJson).get("toolNames") ?: return emptyList()
            when {
                node.isArray -> node.mapNotNull { it.asText().takeIf(String::isNotBlank) }
                node.isTextual -> listOfNotNull(node.asText().takeIf(String::isNotBlank))
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Best-effort domain of a sanitized tool name (`coding_read` → `coding`). */
    private fun domainOf(name: String): String = name.substringBefore('_')

    /**
     * Synthesize the `system.listTools` result: names + first-line descriptions
     * of the tools that are NOT yet exposed, bounded to [limit] lines.
     */
    fun listToolsResult(
        requestId: String,
        allSpecs: Map<String, LangChain4jToolSpec>,
        exposedNames: Set<String>,
        domain: String? = null,
        limit: Int = 200,
    ): ToolExecutionResultMessage {
        val hidden = allSpecs.values
            .asSequence()
            .filter { it.name() !in exposedNames && it.name() !in META_NAMES }
            .filter { domain.isNullOrBlank() || domainOf(it.name()) == domain }
            .sortedBy { it.name() }
            .toList()

        val text = buildString {
            appendLine("Available tools not yet exposed: ${hidden.size}")
            hidden.take(limit).forEach { spec ->
                val desc = spec.description()?.lineSequence()?.firstOrNull()?.take(100)?.trim().orEmpty()
                appendLine("${spec.name()} — $desc")
            }
            val more = hidden.size - limit
            if (more > 0) appendLine("... and $more more")
            appendLine("Hint: call system.exposeTools([\"toolName1\", \"toolName2\"]) to enable the tools you need.")
        }
        return ToolExecutionResultMessage.from(requestId, LIST_TOOLS_NAME, text.trim())
    }

    /**
     * Synthesize the `system.exposeTools` result and EXPAND [exposed] in place,
     * so the loop's very next request carries the new specifications.
     *
     * @return the result message; unknown/ambiguous names and already-exposed
     *   tools are reported as skipped, never errors.
     */
    fun exposeToolsResult(
        requestId: String,
        argumentsJson: String?,
        allSpecs: Map<String, LangChain4jToolSpec>,
        exposed: MutableList<LangChain4jToolSpec>,
    ): ToolExecutionResultMessage {
        val enabled = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (name in parseToolNames(argumentsJson)) {
            val spec = resolveSpec(name, allSpecs)
            when {
                spec == null -> skipped += "$name (unknown or ambiguous)"
                exposed.any { it.name() == spec.name() } -> skipped += "${spec.name()} (already exposed)"
                else -> {
                    exposed += spec
                    enabled += spec.name()
                }
            }
        }

        val text = buildString {
            if (enabled.isNotEmpty()) appendLine("Enabled: ${enabled.joinToString(", ")}")
            if (skipped.isNotEmpty()) appendLine("Skipped: ${skipped.joinToString(", ")}")
            appendLine("Currently exposed: ${exposed.size} tools; see system.listTools for the rest.")
        }
        return ToolExecutionResultMessage.from(requestId, EXPOSE_TOOLS_NAME, text.trim())
    }

    /**
     * Resolve a requested tool name against the full registry: exact
     * `domain_method` first, then a bare method name — but only when it maps
     * to exactly one domain (ambiguity must not silently pick a tool).
     */
    private fun resolveSpec(name: String, allSpecs: Map<String, LangChain4jToolSpec>): LangChain4jToolSpec? {
        allSpecs[name]?.let { return it }
        val matches = allSpecs.values.filter { it.name().endsWith("_$name") }
        return matches.singleOrNull()
    }
}
