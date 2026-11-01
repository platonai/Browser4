package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Browser4 module topology — a STATIC snapshot of which Maven modules depend on
 * which, kept in sync with the real pom.xml graph.
 *
 * Used by the coding domain's `impact` tool and [DevTaskPlanner] as the
 * no-I/O fallback (the LIVE graph from [ModuleGraph] is preferred whenever the
 * workspace is available). The snapshot is validated against the real repo by
 * [ModuleMapDriftE2ETest]: adding a module to the poms without syncing this map
 * fails the test, and the test message names the missing modules.
 *
 * The map mirrors the actual pom.xml dependency edges of the Browser4
 * repository. When the module graph changes, this map is regenerated from the
 * poms rather than being a hand-maintained contract.
 */
object ModuleMap {

    /** Known modules (module dirs containing a real pom.xml, incl. aggregators). */
    val MODULES: List<String> = listOf(
        "browser4",
        "browser4-agent-tools",
        "browser4-agentic",
        "browser4-apps",
        "browser4-apps/browser4-bundle",
        "browser4-apps/browser4-standalone",
        "browser4-boot",
        "browser4-coding",
        "browser4-core",
        "browser4-core/browser4-browser",
        "browser4-core/browser4-common",
        "browser4-core/browser4-parse",
        "browser4-core/browser4-protocol",
        "browser4-core/browser4-resources",
        "browser4-core/browser4-skeleton",
        "browser4-dependencies",
        "browser4-pdk",
        "browser4-pdk/browser4-pdk-bom",
        "browser4-pdk/browser4-pdk-test-plugin",
        "browser4-pdk/browser4-plugin-archetype",
        "browser4-plugins",
        "browser4-plugins/browser4-captcha",
        "browser4-plugins/browser4-images",
        "browser4-plugins/browser4-markdown",
        "browser4-plugins/browser4-media",
        "browser4-plugins/browser4-pptx",
        "browser4-plugins/browser4-seo",
        "browser4-plugins/browser4-swarm",
        "browser4-plugins/browser4-profile-import",
        "browser4-plugins/browser4-summary-demo",
        "browser4-rest",
        "browser4-tests",
        "browser4-tests/browser4-e2e-tests",
        "browser4-tests/browser4-rest-tests",
        "browser4-tests/pulsar-tests-common",
        "examples",
        "examples/browser4-examples",
    )

    /** module -> modules that directly depend on it (reverse edges from the real poms). */
    val DEPENDENTS: Map<String, List<String>> = mapOf(
        "browser4" to listOf(
            "browser4-agent-tools", "browser4-agentic",
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-boot", "browser4-coding", "browser4-core",
            "browser4-core/browser4-browser", "browser4-core/browser4-common",
            "browser4-core/browser4-resources", "browser4-core/browser4-skeleton",
            "browser4-plugins", "browser4-rest", "browser4-tests",
            "browser4-tests/browser4-e2e-tests", "browser4-tests/browser4-rest-tests",
            "browser4-tests/pulsar-tests-common", "examples/browser4-examples",
        ),
        "browser4-agent-tools" to listOf(
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-boot", "browser4-plugins/browser4-swarm",
            "browser4-rest",
        ),
        "browser4-agentic" to listOf(
            "browser4-agent-tools",
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-boot", "browser4-pdk/browser4-pdk-test-plugin",
            "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-images",
            "browser4-plugins/browser4-markdown", "browser4-plugins/browser4-media",
            "browser4-plugins/browser4-pptx",
            "browser4-plugins/browser4-seo", "browser4-plugins/browser4-swarm",
            "browser4-plugins/browser4-profile-import",
            "browser4-rest", "browser4-tests/browser4-e2e-tests",
            "examples/browser4-examples",
        ),
        "browser4-boot" to listOf(
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone", "browser4-rest",
        ),
        "browser4-coding" to listOf("browser4-agentic"),
        "browser4-core" to listOf(
            "browser4-core/browser4-parse", "browser4-core/browser4-protocol",
        ),
        "browser4-core/browser4-browser" to listOf(
            "browser4-agentic", "browser4-core/browser4-protocol",
            "browser4-core/browser4-skeleton", "browser4-pdk/browser4-pdk-test-plugin",
            "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-images",
            "browser4-plugins/browser4-markdown", "browser4-plugins/browser4-media",
            "browser4-plugins/browser4-pptx", "browser4-rest",
        ),
        "browser4-core/browser4-common" to listOf(
            "browser4-agentic", "browser4-core/browser4-browser",
            "browser4-core/browser4-skeleton", "browser4-pdk/browser4-pdk-test-plugin",
            "browser4-tests/pulsar-tests-common", "examples/browser4-examples",
        ),
        "browser4-core/browser4-parse" to listOf("browser4-agent-tools"),
        "browser4-core/browser4-protocol" to listOf(
            "browser4-agentic",
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-boot", "browser4-pdk/browser4-pdk-test-plugin",
            "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-images",
            "browser4-plugins/browser4-markdown", "browser4-plugins/browser4-media",
            "browser4-plugins/browser4-pptx",
            "browser4-plugins/browser4-seo", "browser4-plugins/browser4-swarm",
            "browser4-rest", "examples/browser4-examples",
        ),
        "browser4-core/browser4-resources" to listOf(
            "browser4-agentic",
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-core/browser4-parse", "browser4-core/browser4-protocol",
            "browser4-core/browser4-skeleton", "browser4-rest",
        ),
        "browser4-core/browser4-skeleton" to listOf(
            "browser4-agent-tools", "browser4-agentic",
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-boot", "browser4-core/browser4-parse",
            "browser4-core/browser4-protocol", "browser4-pdk/browser4-pdk-test-plugin",
            "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-images",
            "browser4-plugins/browser4-markdown", "browser4-plugins/browser4-media",
            "browser4-plugins/browser4-pptx",
            "browser4-plugins/browser4-seo", "browser4-plugins/browser4-swarm",
            "browser4-plugins/browser4-profile-import",
            "browser4-plugins/browser4-summary-demo",
            "browser4-rest", "examples/browser4-examples",
        ),
        "browser4-pdk" to listOf(
            "browser4-pdk/browser4-pdk-test-plugin", "browser4-pdk/browser4-plugin-archetype",
            "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-images",
            "browser4-plugins/browser4-markdown", "browser4-plugins/browser4-media",
            "browser4-plugins/browser4-pptx",
            "browser4-plugins/browser4-seo",
            "browser4-plugins/browser4-swarm",
            "browser4-plugins/browser4-profile-import",
            "browser4-plugins/browser4-summary-demo",
        ),
        "browser4-plugins/browser4-swarm" to listOf(
            "browser4-tests/browser4-rest-tests",
        ),
        "browser4-rest" to listOf(
            "browser4-apps/browser4-bundle", "browser4-apps/browser4-standalone",
            "browser4-tests/browser4-rest-tests",
        ),
        "browser4-tests/pulsar-tests-common" to listOf(
            "browser4-core/browser4-skeleton", "browser4-rest",
            "browser4-tests/browser4-e2e-tests", "browser4-tests/browser4-rest-tests",
            "examples/browser4-examples",
        ),
    )

    /** The Rust CLI crate (not a Maven module). */
    const val CLI_CRATE = "browser4-cli"

    /** The Rust CLI crate's directory, relative to the workspace root. */
    const val CLI_CRATE_DIR = "cli/browser4-cli"

    /**
     * The directory the cargo commands have to run in, relative to the workspace root.
     *
     * The CLI crate lives in [CLI_CRATE_DIR] and the workspace root carries no
     * `Cargo.toml`, so a cargo command run from the root fails with "could not find
     * `Cargo.toml`" before it ever looks at the crate. Returns null when the crate
     * directory is absent — the caller then falls back to the workspace root and
     * cargo reports the real problem instead of the shell silently creating an
     * empty directory.
     */
    fun cliCrateWorkingDir(workspaceRoot: Path): String? {
        val crateDir = workspaceRoot.resolve(CLI_CRATE_DIR)
        return if (Files.isDirectory(crateDir)) CLI_CRATE_DIR else null
    }

    /**
     * All modules transitively affected when [module] changes (module + its
     * direct dependents, transitively closed). Static fallback — the coding
     * domain prefers [ModuleGraph.transitiveDependents] on the live graph.
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

    /**
     * Suggested test command for a Maven module.
     *
     * @param testClass optional test-class filter (`-Dtest=...`, comma-separated
     *   for multiple) — the smallest scope when the task names specific tests.
     *
     * With `-am` every upstream reactor module runs its test phase too; a
     * `-Dtest` filter that matches nothing there fails surefire with
     * "No tests matching pattern" — so `failIfNoSpecifiedTests=false` is added
     * whenever a filter is present.
     */
    fun mavenTestCommand(module: String, testClass: String? = null): String {
        val testArg = if (testClass.isNullOrBlank()) "" else " -Dtest=$testClass"
        val noSpecifiedTestsFlag = if (testClass.isNullOrBlank()) "" else " -Dsurefire.failIfNoSpecifiedTests=false"
        return "mvn test -pl $module -am$testArg -DskipTests=false$noSpecifiedTestsFlag"
    }

    /** Suggested test command for the Rust CLI. */
    fun cargoTestCommand(): String = "cargo test --bin browser4-cli"
}
