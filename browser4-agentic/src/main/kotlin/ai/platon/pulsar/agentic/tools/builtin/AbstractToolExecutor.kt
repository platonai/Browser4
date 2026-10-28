package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.ExtractResult
import ai.platon.pulsar.agentic.model.DirectValue
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.TcException
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.api.model.JsEvaluation
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

interface ToolExecutor {

    val domain: String
    val receiverClass: KClass<*>

    /**
     * Whether [callFunctionOn] consumes the receiver.
     *
     * Executors that hold their collaborators as fields (crawl → `CrawlService`,
     * memory → a fallback backend, experience → a `KnowledgeStore`) don't need
     * one, and the dispatcher must not refuse to run them merely because no
     * target object was bound for their domain — that made every business-domain
     * tool advertised by the standard MCP server fail with "no target object is
     * available". Keep `true` (the default) for executors that cast the receiver
     * (page-bound tools, `html_snapshot` → `ManagedSession`, `command`).
     */
    val requiresReceiver: Boolean get() = true

    suspend fun callFunctionOn(tc: ToolCall, receiver: Any = Any()): TcEvaluate

    fun help(): String
    fun help(method: String): String

    /**
     * Returns all tool specifications registered in this executor, keyed by method name.
     */
    fun getToolSpecs(): Map<String, ToolSpec>
}

abstract class AbstractToolExecutor : ToolExecutor {

    private val logger = getLogger(AbstractToolExecutor::class)

    protected val toolSpec = mutableMapOf<String, ToolSpec>()

    override fun getToolSpecs(): Map<String, ToolSpec> = toolSpec.toMap()

    override fun help(): String {
        return toolSpec.values.mapNotNull { it.description }.joinToString("\n")
    }

    /**
     * In-band usage reference for one method: the signature, what each argument
     * means, and how a call looks.
     *
     * This is the channel an MCP client reaches through the `help` tool, so it
     * carries the same information as the generated documentation — argument
     * descriptions harvested from the source KDoc and the spec's examples.
     */
    override fun help(method: String): String {
        val spec = toolSpec[method] ?: return ""
        return renderHelp(spec)
    }

    protected fun renderHelp(spec: ToolSpec): String = buildString {
        appendLine(spec.description ?: spec.expression)
        appendLine(spec.expression)

        val documented = spec.arguments.filter { !it.description.isNullOrBlank() }
        if (documented.isNotEmpty()) {
            appendLine()
            appendLine("Arguments:")
            documented.forEach { arg -> appendLine("  ${arg.name}: ${arg.description}") }
        }

        val examples = spec.examples
        if (examples.isNotEmpty()) {
            appendLine()
            appendLine("Examples:")
            examples.forEach { example ->
                val label = example.title?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
                when {
                    example.executable ->
                        appendLine("  $label${example.args.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                    example.code != null ->
                        appendLine("  $label${example.code.lineSequence().first().trim()}")
                }
                example.notes?.takeIf { it.isNotBlank() }?.let { appendLine("    note: $it") }
            }
        }

        // Authored help adds caveats the KDoc does not repeat. It conventionally
        // opens by repeating the callable signature, which this function has just
        // rendered — emitting it twice is how every plugin's usage-error help
        // ("Get media file metadata … help: media.getInfo(filePath: String) /
        // media.getInfo(filePath: String) / Returns …") ended up duplicated.
        val extra = spec.help
            ?.takeIf { it.isNotBlank() && it != spec.description }
            ?.let { stripAlreadyRenderedHeader(it, spec.expression, spec.description) }
            ?.takeIf { it.isNotBlank() }
        if (extra != null) {
            appendLine()
            append(extra)
        }
    }.trimEnd()

    override suspend fun callFunctionOn(tc: ToolCall, receiver: Any): TcEvaluate {
        val domain = tc.domain
        val functionName = tc.method
        val args = tc.arguments
        val pseudoExpression = tc.pseudoExpression

        return try {
            val r = callFunctionOn(domain, functionName, args, receiver)

            val (value, className) = when {
                r is JsEvaluation -> {
                    val c = when {
                        r.cdpType == "undefined" -> "undefined"
                        r.cdpType == "object" && r.cdpSubtype == "null" -> "null"
                        !r.className.isNullOrBlank() -> r.className
                        r.value != null -> r.value!!::class.qualifiedName
                        else -> "null"
                    }
                    r.value to c
                }
                r == null -> null to "null"
                r == Unit -> null to null
                else -> {
                    val qualifiedName = r::class.qualifiedName
                    if (r is String || r is Number || r is Boolean
                        || r is Map<*, *> || r is Collection<*> || r is Array<*>
                        || r is DirectValue
                    ) {
                        r to qualifiedName
                    } else {
                        // Wrap non-serializable domain objects in a description map
                        // to prevent Jackson from walking into internal object graphs
                        // (e.g. PulsarWebDriver → browser → settings → Spring Environment → ...)
                        if (r is ExtractResult) {
                            // agent.extract: a stable, documented envelope whose
                            // `description` holds the CLEAN schema payload (extraction
                            // fields only — task bookkeeping like token counts lives in
                            // inference events/logs, never inside the payload).  The
                            // truthful completion flag is envelope-level:
                            // completed=true whenever usable content is present, so a
                            // successful extraction never reports "completed": false.
                            mapOf(
                                "type" to qualifiedName,
                                "description" to r.toString(),
                                "completed" to r.completed
                            ) to qualifiedName
                        } else {
                            mapOf(
                                "type" to qualifiedName,
                                "description" to r.toString()
                            ) to qualifiedName
                        }
                    }
                }
            }
            // JS results the model cannot use must say so. This is the last layer that
            // still sees the CDP type/subtype: the CLI only receives text
            // (`MCPToolController` renders the value), so a Promise used to arrive as
            // `{}` and an undefined as a bare `null` (2026-10-28: three `eval`s of a
            // 20-page batch silently lost their data).
            val issue = jsEvaluationIssue(r as? JsEvaluation)
            val evaluate = TcEvaluate(
                value = issue?.text ?: value,
                className = if (issue != null) null else className,
                expression = pseudoExpression,
            )
            if (issue?.failure == true) {
                evaluate.exception = TcException(
                    pseudoExpression,
                    IllegalArgumentException(issue.text),
                    help(functionName),
                )
            }
            evaluate
        } catch (e: Exception) {
            logger.warn("Error executing expression: {} - {}", pseudoExpression, e.brief())
            val h = help(functionName)
            TcEvaluate(pseudoExpression, e, help = h)
        }
    }

    @Throws(IllegalArgumentException::class)
    abstract suspend fun callFunctionOn(domain: String, functionName: String, args: Map<String, Any?>, receiver: Any): Any?

    // ---------------- Shared helpers for named parameter executors ----------------
    protected fun validateArgs(
        args: Map<String, Any?>,
        allowed: Set<String>,
        required: Set<String> = allowed,
        functionName: String
    ) {
        required.forEach {
            if (!args.containsKey(it)) throw IllegalArgumentException("Missing required parameter '$it' for $functionName")
        }
        args.keys.forEach {
            if (it !in allowed) throw IllegalArgumentException("Extraneous parameter '$it' for $functionName. Allowed=$allowed")
        }
    }

    protected fun paramString(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: String? = null
    ): String? {
        val v = args[name]
        return when (v) {
            null if required && default == null -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            null -> default
            else -> v.toString()
        }
    }

    protected fun paramInt(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Int? = null
    ): Int? {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return default
        }
        return v.toString().toIntOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Int for $functionName | actual='${v}'")
    }

    protected fun paramLong(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Long? = null
    ): Long? {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return default
        }
        return v.toString().toLongOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Long for $functionName | actual='${v}'")
    }

    protected fun paramBool(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Boolean? = null
    ): Boolean? {
        val v = args[name] ?: return when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> default
        }
        return when (v.toString().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Parameter '$name' must be Boolean for $functionName | actual='${v}'")
        }
    }

    protected fun paramStringList(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true
    ): List<String> {
        val v = args[name] ?: when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> return emptyList()
        }
        return when (v) {
            is List<*> -> v.filterIsInstance<String>()
            is Array<*> -> v.filterIsInstance<String>()
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException("Parameter '$name' must be a list[string] or comma separated string for $functionName | actual='${v}'")
        }
    }

    protected fun paramDouble(
        args: Map<String, Any?>,
        name: String,
        functionName: String,
        required: Boolean = true,
        default: Double? = null
    ): Double? {
        val v = args[name] ?: return when {
            required -> throw IllegalArgumentException("Missing parameter '$name' for $functionName")
            else -> default
        }

        return v.toString().toDoubleOrNull()
            ?: throw IllegalArgumentException("Parameter '$name' must be Double for $functionName | actual='${v}'")
    }
}

/**
 * A JS evaluation result the model cannot use as-is.
 *
 * @property text what the model should see instead of the unusable value
 * @property failure true when the call must count as failed (the model has to
 *   change the expression before the data can exist)
 */
internal data class JsEvaluationIssue(val text: String, val failure: Boolean)

/**
 * The unusable part of a [JsEvaluation], or null when the value is real.
 *
 * Both cases are only observable here: `MCPToolController` receives the already
 * rendered text, where a Promise had become `{}` and an undefined a bare `null`.
 * The marker prefix of the undefined notice is consumed by the CLI
 * (`main.rs`, `eval` output) so `eval --json` still emits `null`.
 *
 * @param evaluation the raw evaluation; null for non-JS tool results
 */
internal fun jsEvaluationIssue(evaluation: JsEvaluation?): JsEvaluationIssue? {
    if (evaluation == null) return null
    val subtype = evaluation.cdpSubtype?.lowercase()
    val type = evaluation.cdpType?.lowercase()
    return when {
        // A promise value is not the result: without `--await` the CLI serialized it as
        // `{}`, and the model treated that as the extracted data (2026-10-28 run).
        subtype == "promise" -> JsEvaluationIssue(
            "The expression returned an unresolved Promise, not a value. " +
                "Re-run it with --await (or await it in the expression) so the resolved " +
                "value is returned; a Promise object is not the result.",
            failure = true,
        )
        // Legitimate for side-effect expressions, so it stays a success — but "null"
        // alone taught the model nothing about why there was no data.
        type == "undefined" -> JsEvaluationIssue(
            "$UNDEFINED_RESULT_PREFIX: the expression produced no value. Make it return one " +
                "(a final expression, or `return` inside a function) if you need the data.",
            failure = false,
        )
        else -> null
    }
}

/**
 * Prefix of the plain-mode notice for a JS `undefined` result.
 *
 * The literal `null` leads so text consumers that look for it keep working; the CLI
 * maps this prefix back to JSON null for `--json`, where a string would be a
 * regression.
 */
internal const val UNDEFINED_RESULT_PREFIX = "null  (undefined"

/**
 * Drop an authored help block's leading line when the framework already rendered
 * that exact line above it.
 *
 * Only an *identical* first line is removed, and blank leading lines are skipped
 * when looking for it: a different opening line is real documentation
 * (`media.compress` documents both of its overloads while only the advertised one
 * is rendered above), so it is kept verbatim.
 *
 * @param help the authored help block
 * @param alreadyRendered lines the caller has already emitted
 * @return [help] with the duplicated opening line (and nothing else) removed
 */
internal fun stripAlreadyRenderedHeader(help: String, vararg alreadyRendered: String?): String {
    val lines = help.lines()
    val firstIdx = lines.indexOfFirst { it.isNotBlank() }
    if (firstIdx < 0) {
        return help
    }
    val first = lines[firstIdx].trim()
    val duplicated = alreadyRendered.any { it != null && it.isNotBlank() && it.trim() == first }
    if (!duplicated) {
        return help
    }
    return lines.drop(firstIdx + 1).joinToString("\n").trim()
}
