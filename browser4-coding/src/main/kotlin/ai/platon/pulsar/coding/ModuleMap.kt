package ai.platon.pulsar.coding

/**
 * Browser4 module topology — a static map of which Maven modules depend on which.
 *
 * Used by the coding domain's `impact` tool to answer "if I change this file,
 * which modules are affected and how do I test them?".
 *
 * The map mirrors the actual pom.xml dependency edges of the Browser4
 * repository. It is intentionally a small, stable snapshot of the current
 * topology — the anti-staleness philosophy applies here too: when the module
 * graph changes, this map is regenerated from the poms rather than being a
 * hand-maintained contract.
 */
object ModuleMap {

    /** Known top-level and nested Maven modules. */
    val MODULES: List<String> = listOf(
        "browser4-core/browser4-common",
        "browser4-core/browser4-resources",
        "browser4-core/browser4-skeleton",
        "browser4-core/browser4-protocol",
        "browser4-core/browser4-browser",
        "browser4-core/browser4-parse",
        "browser4-agent-tools",
        "browser4-agentic",
        "browser4-coding",
        "browser4-boot",
        "browser4-plugins",
        "browser4-rest",
    )

    /** module -> modules that directly depend on it (reverse edges). */
    val DEPENDENTS: Map<String, List<String>> = mapOf(
        "browser4-core/browser4-common" to listOf(
            "browser4-core/browser4-skeleton", "browser4-core/browser4-protocol",
            "browser4-agentic", "browser4-rest", "browser4-boot", "browser4-agent-tools",
        ),
        "browser4-core/browser4-skeleton" to listOf(
            "browser4-core/browser4-protocol", "browser4-agentic", "browser4-boot",
            "browser4-rest", "browser4-agent-tools",
        ),
        "browser4-core/browser4-protocol" to listOf(
            "browser4-core/browser4-browser", "browser4-agentic", "browser4-boot",
            "browser4-rest", "browser4-agent-tools",
        ),
        "browser4-core/browser4-browser" to listOf("browser4-agentic", "browser4-rest"),
        "browser4-core/browser4-parse" to listOf("browser4-rest"),
        "browser4-agentic" to listOf("browser4-boot", "browser4-rest", "browser4-agent-tools"),
        "browser4-coding" to listOf("browser4-agentic"),
        "browser4-agent-tools" to listOf("browser4-rest"),
        "browser4-boot" to listOf("browser4-rest"),
    )

    /** The Rust CLI crate (not a Maven module). */
    const val CLI_CRATE = "browser4-cli"

    /** Modules that contain a `cli/` or Rust portion. */
    val CLI_AFFECTED_MODULES: List<String> = listOf(CLI_CRATE)

    /**
     * All modules transitively affected when [module] changes (module + its
     * direct dependents, transitively closed).
     */
    fun transitiveDependents(module: String): List<String> {
        val result = linkedSetOf(module)
        var frontier = listOf(module)
        while (frontier.isNotEmpty()) {
            val next = frontier.flatMap { DEPENDENTS[it].orEmpty() }
                .filter { result.add(it) }
            frontier = next
        }
        return result.toList()
    }

    /** Suggested test command for a Maven module. */
    fun mavenTestCommand(module: String): String = "mvn test -pl $module -am -DskipTests=false"

    /** Suggested test command for the Rust CLI. */
    fun cargoTestCommand(): String = "cargo test --bin browser4-cli"
}
