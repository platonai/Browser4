package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor

/**
 * Consistency gate for the tool registry.
 *
 * Every advertised tool's documentation and schema are derived from its
 * [ToolSpec], so a spec that is missing a description — or that renders a
 * signature no human can read — silently degrades the interface an MCP client
 * sees. This lint makes that visible in tests instead of in the field:
 *
 * - `ERROR` rules are the ones that already hold for the whole registry; a
 *   violation fails the build.
 * - `WARNING` rules are the gaps the documentation work (Phase 1 of
 *   `docs-dev/copilot/mcp-interface-hardening-plan.md`) still has to close;
 *   they are reported so progress is measurable, not enforced yet.
 */
object ToolSpecLint {

    enum class Severity { ERROR, WARNING }

    data class Issue(val severity: Severity, val tool: String, val message: String)

    /** Renders like `Arg(name=url, type=String, ...)` — the JVM data-class leak. */
    private const val DATA_CLASS_LEAK = "Arg("

    /** Renders like `url: String = ` — an argument marked optional by an empty default. */
    private const val EMPTY_DEFAULT_SUFFIX = "= )"

    fun check(specs: Collection<ToolSpec>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val byName = mutableMapOf<String, MutableList<ToolSpec>>()

        for (spec in specs) {
            val tool = if (spec.domain.isBlank() || spec.method.isBlank()) {
                "<malformed:${spec.domain}.${spec.method}>"
            } else {
                "${spec.domain}.${spec.method}"
            }

            if (spec.domain.isBlank()) issues += Issue(Severity.ERROR, tool, "domain is blank")
            if (spec.method.isBlank()) issues += Issue(Severity.ERROR, tool, "method is blank")

            byName.getOrPut("${spec.domain}.${spec.method}") { mutableListOf() } += spec

            if (spec.description.isNullOrBlank()) {
                issues += Issue(Severity.ERROR, tool, "description is blank: a client cannot tell what the tool does")
            }
            if (spec.help.isNullOrBlank()) {
                issues += Issue(Severity.WARNING, tool, "no help text: usage details are unavailable in-band")
            }
            if (spec.returnType.isBlank()) {
                issues += Issue(Severity.WARNING, tool, "returnType is blank")
            }

            if (spec.expression.contains(DATA_CLASS_LEAK)) {
                issues += Issue(
                    Severity.ERROR, tool,
                    "signature leaks a data-class dump: ${spec.expression}"
                )
            }
            if (spec.expression.contains(EMPTY_DEFAULT_SUFFIX) || spec.expression.endsWith("= ")) {
                issues += Issue(
                    Severity.ERROR, tool,
                    "signature has an empty default (argument would be advertised as optional): ${spec.expression}"
                )
            }

            for (arg in spec.arguments) {
                val argRef = "$tool(${arg.name})"
                if (arg.name.isBlank()) issues += Issue(Severity.ERROR, tool, "argument with blank name")
                if (arg.type.isBlank()) issues += Issue(Severity.ERROR, argRef, "argument type is blank")
                if (arg.description.isNullOrBlank()) {
                    issues += Issue(
                        Severity.WARNING, argRef,
                        "no argument description: the schema falls back to '${arg.expression}'"
                    )
                }
                if (arg.defaultValue == null && !isJsonRepresentable(arg.type)) {
                    // WARNING here because this lint also runs over the *raw*
                    // generator output, where upstream overloads
                    // (`navigate(entry: NavigateEntry)`, `screenshot(rect: RectD)`)
                    // are legitimately present. The gate with teeth is
                    // `ToolSpecLintTest.advertisedSpecsAreCallable`, which asserts
                    // this over the specs the product actually advertises.
                    issues += Issue(
                        Severity.WARNING, argRef,
                        "required argument of type '${arg.type}' cannot be sent by an MCP client " +
                            "(JSON has no such value) — declare the primitive form the executor reads, " +
                            "or give it a default"
                    )
                }
            }

            spec.cliName?.let { cliName ->
                if (cliName.contains('-')) {
                    issues += Issue(
                        Severity.ERROR, tool,
                        "cliName '$cliName' must use the spaced form ('profile import'), not kebab-case"
                    )
                }
            }
        }

        // A method may be declared with several signatures (Kotlin overloads); the
        // registry keeps one spec per name, so an overload is a warning. Only two
        // specs with the *same* signature are a genuine duplicate.
        for ((tool, group) in byName) {
            if (group.size < 2) continue
            val signatures = group.map { spec -> spec.arguments.joinToString(",") { "${it.name}:${it.type}" } }
            if (signatures.size != signatures.toSet().size) {
                issues += Issue(Severity.ERROR, tool, "duplicate tool: the same signature is declared twice")
            }
            if (signatures.toSet().size > 1) {
                issues += Issue(
                    Severity.WARNING, tool,
                    "overloaded method (${signatures.size} signatures): only one is advertised"
                )
            }
        }

        return issues
    }

    /** Lint the specs of every executor handed in (built-in or plugin-registered). */
    fun checkExecutors(executors: Collection<ToolExecutor>): List<Issue> =
        check(executors.flatMap { it.getToolSpecs().values })

    fun errors(issues: Collection<Issue>): List<Issue> = issues.filter { it.severity == Severity.ERROR }

    fun warnings(issues: Collection<Issue>): List<Issue> = issues.filter { it.severity == Severity.WARNING }

    /** A multi-line report suitable for an assertion message. */
    fun report(issues: Collection<Issue>): String = issues
        .sortedWith(compareBy({ it.severity }, { it.tool }, { it.message }))
        .joinToString("\n") { "${it.severity} ${it.tool}: ${it.message}" }

    /**
     * Whether a client can produce a value of this declared type from JSON.
     *
     * A *required* argument of any other type is unfulfillable over MCP: the
     * generated `tools/list` schema would demand a `NavigateEntry`/`RectD`/
     * `Duration`/`AriaSnapshotOptions` object or a `suspend () -> …` action, and
     * the required-argument check then rejects the call before it is dispatched.
     * That is exactly how `tab.navigate` broke (`entry: NavigateEntry` was
     * advertised while the executor reads `url`).
     */
    fun isJsonRepresentable(type: String): Boolean {
        val base = type.trim().removeSuffix("?").substringBefore('<').trim()
        if (base in JSON_PRIMITIVES) return true
        // Collections of a representable element type are fine (List<String>, Array<Int>).
        val element = type.substringAfter('<', "").substringBeforeLast('>', "").trim().removeSuffix("?")
        return element.isNotEmpty() && element.substringBefore(',').trim() in JSON_PRIMITIVES
    }

    private val JSON_PRIMITIVES = setOf(
        "String", "Int", "Long", "Double", "Float", "Boolean",
        "Any", "Number", "List", "Array", "Map", "Set", "Collection", "JsonObject", "JsonElement",
    )
}
