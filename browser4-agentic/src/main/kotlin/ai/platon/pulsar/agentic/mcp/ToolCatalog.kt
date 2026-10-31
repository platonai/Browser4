package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor

/**
 * Where an advertised MCP tool comes from.
 *
 * The label travels to logs and conflict reports, so it must keep naming the
 * exact source the tool was discovered from.
 */
enum class CatalogSource(val label: String) {
    /** A tool of a built-in executor (`tab`, `browser`, `agent`, `coding`, `b4`, `system`, `skill`). */
    BUILT_IN("built-in"),

    /** A tool of an executor registered through `CustomToolRegistry` (plugin/business domains, `session`). */
    CUSTOM("custom"),
}

/**
 * One advertised MCP tool: the name a client calls, the `(domain, method)` it
 * resolves to, and the spec both channels render from.
 *
 * @property name the advertised MCP name (`navigate`, `browser_type`, `open_session`)
 * @property domain the executor domain the call resolves to
 * @property method the executor method the call resolves to
 * @property spec the tool definition; `null` for an alias whose canonical tool is
 *   not registered in the channel building the catalog (a name a channel
 *   advertises without being able to execute yet — see
 *   [ToolCatalog.Companion.build])
 * @property source where the tool was discovered
 * @property canonicalName the canonical MCP name of the tool this entry
 *   advertises; equals [name] unless this entry is an alias spelling
 */
data class CatalogEntry(
    val name: String,
    val domain: String,
    val method: String,
    val spec: ToolSpec?,
    val source: CatalogSource,
    val canonicalName: String = name,
) {
    /** Whether this entry is a second spelling of another tool. */
    val isAlias: Boolean get() = canonicalName != name

    /** `domain.method`, the form used in logs and conflict reports. */
    val expression: String get() = "$domain.$method"
}

/**
 * Two different tools claiming the same advertised name.
 *
 * Reported instead of silently overwritten: the first registration wins, and the
 * shadowed one would otherwise never reach a client.
 */
data class CatalogConflict(
    /** The contested advertised name. */
    val name: String,
    /** `domain.method` of the tool that kept the name. */
    val kept: String,
    /** `domain.method` of the tool that lost it. */
    val shadowed: String,
)

/** A set of executors discovered from the same place. */
data class ExecutorGroup(
    val executors: Collection<ToolExecutor>,
    val source: CatalogSource,
)

/**
 * The single source of truth for the tool surface of both MCP channels.
 *
 * Browser4 exposes the same tools through two channels — the standard MCP server
 * (`Browser4MCPServer`) and the private dispatcher (`MCPToolController`) — and a
 * model that learned a name on one channel must not get "unknown tool" on the
 * other. Keeping the two enumerations in separate classes is how they drifted
 * apart before: one listed `browser_tabs` and the other did not, one spelled a
 * session tool `open_session` and the other could not express it at all.
 *
 * The catalog is a pure, side-effect-free enumeration. Each channel feeds it the
 * executors it can reach and adapts the result to its own transport:
 *
 * - the standard MCP server registers every entry that has a spec;
 * - the private dispatcher publishes the names, and executes them through its
 *   own dispatch table.
 *
 * ## Naming
 *
 * A canonical tool's name is derived from its `(domain, method)` by
 * [McpToolNames.toMcpToolName]. A tool whose public spelling differs from that
 * derived form (the session lifecycle tools: `session.open` is advertised as
 * `open_session`) is renamed by [renames].
 *
 * Aliases ([McpToolAlias]) are added as additional spellings of a registered
 * canonical tool. An alias whose canonical tool is not registered is skipped —
 * unless [includeUnresolvedAliases] is set, which a name-only channel (the
 * dispatcher's static segment) needs so a readiness probe still sees
 * `browser_navigate` before any session exists.
 */
class ToolCatalog private constructor(
    /** Advertised tools by MCP name, in registration order (built-in before custom). */
    val entries: Map<String, CatalogEntry>,
    /** Name clashes detected while building; empty when every name was unique. */
    val conflicts: List<CatalogConflict> = emptyList(),
) {

    /** Every advertised name, in registration order. */
    val names: List<String> get() = entries.keys.toList()

    /** Number of advertised tools. */
    val size: Int get() = entries.size

    /** The entry advertised under [name], or `null`. */
    fun get(name: String): CatalogEntry? = entries[name]

    /** Whether [name] is advertised. */
    fun contains(name: String): Boolean = entries.containsKey(name)

    /** The entries a channel can actually execute (every alias resolved to a spec). */
    fun executable(): List<CatalogEntry> = entries.values.filter { it.spec != null }

    override fun toString(): String = "ToolCatalog(size=$size, conflicts=${conflicts.size})"

    companion object {

        /**
         * Enumerate the advertised tools.
         *
         * @param groups executors in priority order. The first group wins a name
         *   clash; the loser is reported through [conflicts] instead of silently
         *   disappearing.
         * @param aliases additional spellings to advertise for a canonical tool.
         * @param renames explicit advertised names for `(domain, method)` pairs
         *   whose derived name is not their public spelling, keyed `domain.method`.
         * @param includeUnresolvedAliases advertise an alias whose canonical tool
         *   is not registered, as a name-only entry (no [CatalogEntry.spec]). Used
         *   by the dispatcher's static segment, which must list `browser_navigate`
         *   before any session — and therefore any tab tool — exists.
         */
        fun build(
            groups: List<ExecutorGroup>,
            aliases: List<McpToolAlias> = McpToolNames.frontendAliases,
            renames: Map<String, String> = McpToolNames.sessionRenames,
            includeUnresolvedAliases: Boolean = false,
        ): ToolCatalog {
            val entries = linkedMapOf<String, CatalogEntry>()
            val conflicts = mutableListOf<CatalogConflict>()

            for (group in groups) {
                for (executor in group.executors) {
                    for ((method, spec) in executor.getToolSpecs()) {
                        val derived = McpToolNames.toMcpToolName(executor.domain, method)
                        val name = renames["${executor.domain}.$method"] ?: derived
                        val existing = entries[name]
                        if (existing != null) {
                            // Re-scanning the same executor is normal; only a genuine
                            // clash between two different tools is worth reporting.
                            if (existing.domain != executor.domain || existing.method != method) {
                                conflicts += CatalogConflict(
                                    name,
                                    kept = existing.expression,
                                    shadowed = "${executor.domain}.$method",
                                )
                            }
                            continue
                        }
                        entries[name] = CatalogEntry(name, executor.domain, method, spec, group.source)
                    }
                }
            }

            for (alias in aliases) {
                if (entries.containsKey(alias.frontendName)) continue

                // Resolve the canonical tool by (domain, method), not by its
                // derived name: a renamed tool (`session.windowState` →
                // `window_state`) is still the tool an alias points at.
                val canonical = entries.values.firstOrNull {
                    it.domain == alias.domain && it.method == alias.method
                }

                entries[alias.frontendName] = when {
                    canonical != null -> canonical.copy(
                        name = alias.frontendName,
                        canonicalName = canonical.name,
                    )
                    includeUnresolvedAliases -> CatalogEntry(
                        name = alias.frontendName,
                        domain = alias.domain,
                        method = alias.method,
                        spec = null,
                        source = CatalogSource.CUSTOM,
                        canonicalName = alias.canonicalName,
                    )
                    else -> continue
                }
            }

            return ToolCatalog(entries, conflicts)
        }
    }
}
