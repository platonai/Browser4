package ai.platon.pulsar.skeleton.workflow.parse.html

/**
 * A pluggable algorithm that produces a compressed page summary for the
 * `htmlsnapshot summary` command.
 *
 * The built-in implementation is [WpsiPageSummaryAlgorithm] (id `"wpsi"`),
 * which generates the deterministic Web Page Summary Index (WPSI) YAML.
 * Third-party plugins can contribute additional algorithms (for example an
 * LLM-based or a readability-focused summary) by implementing this interface
 * and exposing them through a `PageSummaryAlgorithmMount` bean:
 *
 * ```kotlin
 * @AutoConfiguration
 * class MySummaryAutoConfiguration : PageSummaryAlgorithmMount {
 *     override fun getPageSummaryAlgorithms() = listOf(MySummaryAlgorithm())
 * }
 *
 * class MySummaryAlgorithm : PageSummaryAlgorithm {
 *     override val id = "my-summary"
 *     override val displayName = "My Summary"
 *     override val description = "..."
 *     override fun generate(input: PageSummaryInput): String {
 *         // Return YAML, JSON or plain text. The CLI saves the result to a
 *         // file and prints it; only the built-in "wpsi" output gets the
 *         // compact outline rendering.
 *     }
 * }
 * ```
 *
 * The algorithm is selected per call with the `algorithm` argument of the
 * `html_snapshot.summary` MCP tool (CLI: `--algorithm <id>`). When the
 * argument is absent, [PageSummaryAlgorithmRegistry.DEFAULT_ID] is used.
 *
 * Implementations must be thread-safe: the host may invoke [generate] from
 * concurrent MCP calls on different sessions.
 */
interface PageSummaryAlgorithm {

    /**
     * Unique algorithm identifier.
     *
     * Must match `[a-z0-9][a-z0-9-]*` (lowercase tokens separated by single
     * hyphens). The id is stable user-facing API: it appears in the CLI
     * `--algorithm <id>` option and in error messages, so renaming an id is a
     * breaking change.
     */
    val id: String

    /** Human-readable name shown by `htmlsnapshot algorithms`. */
    val displayName: String

    /** One-paragraph description of what the summary contains. */
    val description: String

    /**
     * Optional version label of the algorithm/output format, shown by
     * `htmlsnapshot algorithms`. Null when the algorithm does not version
     * its output.
     */
    val version: String? get() = null

    /**
     * True for algorithms shipped inside the Browser4 core distribution.
     * Plugin-contributed algorithms return the default `false`.
     */
    val builtin: Boolean get() = false

    /**
     * Generate the page summary.
     *
     * @param input the fresh page snapshot and its metadata; never null.
     * @return the summary as a string (YAML, JSON or plain text). The result
     * is returned verbatim over MCP and saved to a `.yml`/text file by the
     * CLI, so it must be self-contained and must not throw for ordinary
     * pages — fail loudly for genuinely unusable input.
     */
    fun generate(input: PageSummaryInput): String
}
