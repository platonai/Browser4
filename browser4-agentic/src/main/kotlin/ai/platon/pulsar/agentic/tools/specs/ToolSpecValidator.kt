package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.ToolErrorCode

/**
 * Validates a tool call against its [ToolSpec] **before** it reaches the executor.
 *
 * Both MCP channels share this validator so the same bad input produces the same
 * code and message: a missing argument is reported as `MISSING_REQUIRED_ARG` with
 * the expected signature (the documentation Phase 1 generated), a wrongly typed
 * value as `INVALID_ARGUMENT`, and — when strict mode is on — an undeclared
 * argument as `UNKNOWN_ARGUMENT`.
 *
 * Failing before dispatch keeps retries idempotent: nothing happens on the
 * browser when the request was malformed.
 *
 * @param strictUnknownArgs when `true`, arguments the spec does not declare are
 *   rejected. Off by default: the dispatcher forwards extra arguments on purpose
 *   (some executors accept documented-but-undeclared parameters such as crawl's
 *   `sql`), and rejecting them would break working calls. Enable with
 *   `-Dmcp.strictArgs=true` once every such parameter is declared.
 */
class ToolSpecValidator(
    private val strictUnknownArgs: Boolean = false,
) {
    /** One contract violation, already mapped to a [ToolErrorCode]. */
    data class Violation(val code: ToolErrorCode, val message: String)

    /**
     * How a channel treats a contract violation when the violated spec comes
     * from a built-in domain (`tab`, `system`, `fs`, …) rather than from an
     * explicitly registered executor.
     *
     * Built-in specs used to mirror the upstream `WebDriver` interface, so they
     * could disagree with what the executor reads (`tab.navigate` advertised
     * `entry: NavigateEntry` while the executor reads `url`). The rollout was
     * therefore staged — observe first, reject later — and both risks have since
     * been closed: every built-in `tab` method is advertised from a spec authored
     * in this repository (`WebDriverToolSpecOverrides`), and the standard MCP
     * server has always enforced the same catalog, so the REST channel now
     * enforces it too ([ERROR] is the default) and answers identically.
     */
    enum class BuiltInPolicy {
        /** No built-in validation at all. */
        OFF,

        /** Validate, log and count, then dispatch anyway — explicit opt-in. */
        SHADOW,

        /** Reject like the standard MCP server does — the default. */
        ERROR,
    }

    /**
     * @param args the arguments about to be forwarded
     * @param contextArgs arguments the transport owns and the executor may read
     *   (e.g. `sessionId`) — never reported as unknown
     */
    fun validate(
        spec: ToolSpec,
        args: Map<String, Any?>,
        contextArgs: Set<String> = DEFAULT_CONTEXT_ARGS,
    ): List<Violation> {
        val violations = mutableListOf<Violation>()

        for (arg in spec.arguments) {
            if (arg.name in contextArgs) continue
            val value = args[arg.name]
            if (arg.defaultValue == null && isMissing(value) && !satisfiedBySibling(arg, args)) {
                violations += Violation(
                    ToolErrorCode.MISSING_REQUIRED_ARG,
                    "missing required argument '${arg.name}' for ${spec.expression}",
                )
                continue
            }
            if (value != null && !fitsType(value, arg.type)) {
                violations += Violation(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "argument '${arg.name}' expects ${arg.type} but got " +
                        "'$value' (${value::class.simpleName}) for ${spec.expression}",
                )
            }
        }

        if (strictUnknownArgs) {
            val declared = spec.arguments.mapTo(mutableSetOf()) { it.name } + contextArgs
            args.keys.filterNot { it in declared }.forEach { unknown ->
                violations += Violation(
                    ToolErrorCode.UNKNOWN_ARGUMENT,
                    "unknown argument '$unknown' for ${spec.expression}; " +
                        "expected ${spec.arguments.joinToString(", ") { it.expression }}",
                )
            }
        }

        return violations
    }

    private fun isMissing(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        else -> false
    }

    /**
     * True when [arg] is optional because the caller supplied the sibling argument
     * that replaces it — `command.run` with `resumeTaskId` and no task text: the
     * instruction comes from the interrupted task's checkpoint.
     */
    private fun satisfiedBySibling(arg: ToolSpec.Arg, args: Map<String, Any?>): Boolean {
        val sibling = arg.requiredUnless?.takeIf { it.isNotBlank() } ?: return false
        return !isMissing(args[sibling])
    }

    /** Type check limited to what the schema advertises and executors parse. */
    private fun fitsType(value: Any?, type: String): Boolean {
        val normalised = type.trimEnd('?').trim().lowercase()
        return when (normalised) {
            "int", "integer", "long", "short" -> value is Number || value.toString().toLongOrNull() != null
            "double", "float", "number" -> value is Number || value.toString().toDoubleOrNull() != null
            "boolean", "bool" -> value is Boolean || value.toString().toBooleanStrictOrNull() != null
            else -> true
        }
    }

    companion object {
        /**
         * Arguments the transport consumes or injects on the executor's behalf.
         *
         * `sessionId` routes the call and `cache` opts out of the result cache; both
         * are stripped before dispatch, and neither is ever reported as an unknown
         * tool argument.
         */
        val DEFAULT_CONTEXT_ARGS: Set<String> = setOf("sessionId", "cache")

        /** Whether `-Dmcp.validateArgs` (default on) enables validation. */
        fun validationEnabled(): Boolean =
            System.getProperty("mcp.validateArgs", "true").toBoolean()

        /** Whether `-Dmcp.strictArgs` (default off) rejects undeclared arguments. */
        fun strictModeEnabled(): Boolean =
            System.getProperty("mcp.strictArgs", "false").toBoolean()

        /**
         * `-Dmcp.validateBuiltinArgs` — `error` (default), `shadow`, or `off`.
         *
         * `error` gives the REST channel the same verdict the standard MCP server
         * has always given for the same bad input. `shadow` stays available for a
         * deployment that wants to observe a mismatch without rejecting the call
         * (the counters are still in `/api/mcp/stats`), and `off` disables built-in
         * validation entirely.
         */
        fun builtInPolicy(): BuiltInPolicy =
            when (System.getProperty("mcp.validateBuiltinArgs", "error").trim().lowercase()) {
                "off", "false", "none", "disabled" -> BuiltInPolicy.OFF
                "shadow", "observe", "warn" -> BuiltInPolicy.SHADOW
                else -> BuiltInPolicy.ERROR
            }

        /** The validator a deployment asks for, honouring the system properties. */
        fun fromSystemProperties(): ToolSpecValidator =
            ToolSpecValidator(strictUnknownArgs = strictModeEnabled())
    }
}
