package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.getLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks a successful tool result against the contract its [ToolSpec] declares.
 *
 * Two failure modes this catches early:
 *
 * 1. **Result shape drift** — the executor starts returning a different object
 *    than the documented `outputSchema` (a renamed field, a string where an
 *    object was promised). Clients compile against the documentation, so a silent
 *    shape change is a breaking change that would otherwise surface in the field.
 * 2. **Task envelope drift** — a task-submitting tool returns something that is
 *    not the shared `{taskId, status, pollAfterMs, statusTool}` envelope, leaving
 *    a generic client unable to poll it.
 *
 * The supported schema subset is deliberately small — `type`, `required`,
 * `properties`, `items`, `enum` — so no JSON-Schema engine is pulled in for what
 * the registry actually declares. Anything outside the subset is ignored rather
 * than guessed.
 *
 * Deployments decide what a violation means:
 * `-Dmcp.validateResults=warn` (default, log + count) or `error` (fail the call,
 * which is what tests use).
 */
object ToolResultValidator {
    private val logger = getLogger(this)

    /** A single contract violation, with the JSON path where it was found. */
    data class Issue(val path: String, val message: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** Violations counted per tool since start (Phase 3 exports this as a metric). */
    private val violationCount = ConcurrentHashMap<String, AtomicLong>()

    /** Parse [text] as JSON, or `null` when it is not JSON at all. */
    fun parse(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.first() != '{' && trimmed.first() != '[') return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }

    /**
     * The shared task envelope for a submit tool whose result is the bare task id.
     *
     * Both channels build it here so a generic client sees the same structure
     * whichever door it came through — including the `cancelTool` when the domain
     * has one, which is how a client learns it can stop the work.
     */
    fun taskEnvelope(taskId: String, policy: TaskPolicy): JsonObject = JsonObject(
        buildMap {
            put("taskId", JsonPrimitive(taskId))
            put("status", JsonPrimitive("running"))
            put("pollAfterMs", JsonPrimitive(policy.pollAfterMs))
            put("statusTool", JsonPrimitive(policy.statusTool))
            put("resultTool", JsonPrimitive(policy.resultTool))
            policy.cancelTool?.takeIf { it.isNotBlank() }?.let { put("cancelTool", JsonPrimitive(it)) }
        }
    )

    /** Whether [text] is the bare task id a submit tool returns rather than JSON. */
    fun isBareTaskId(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[")
    }

    /**
     * Validate [result] against [spec].
     *
     * @param result the structured result: the parsed JSON, or the task envelope
     * @return the violations found (empty when the result honours the contract)
     */
    fun validate(spec: ToolSpec, result: JsonElement?): List<Issue> {
        val issues = mutableListOf<Issue>()

        spec.task?.let { policy ->
            val obj = result as? JsonObject
            if (obj == null) {
                issues += Issue("$", "a task-submitting tool must return the task envelope object")
            } else {
                val taskId = obj["taskId"]?.jsonPrimitiveOrNull()?.contentOrNull()
                if (taskId.isNullOrBlank()) {
                    issues += Issue("$.taskId", "task envelope is missing a non-blank taskId")
                }
                val statusTool = obj["statusTool"]?.jsonPrimitiveOrNull()?.contentOrNull()
                if (statusTool.isNullOrBlank()) {
                    issues += Issue("$.statusTool", "task envelope must name the status tool ('${policy.statusTool}')")
                }
            }
        }

        val schemaText = spec.outputSchema?.takeIf { it.isNotBlank() } ?: return issues
        val schema = runCatching { json.parseToJsonElement(schemaText) as? JsonObject }.getOrNull()
        if (schema == null) {
            logger.warn("Tool '{}' declares an outputSchema that is not a JSON object; skipping validation", spec.expression)
            return issues
        }

        issues += validateAgainst(schema, result, "$")
        return issues
    }

    /**
     * Record violations: log them, count them, and report whether the caller
     * should fail the call.
     *
     * @return `true` when `-Dmcp.validateResults=error` asks the caller to fail
     */
    fun report(spec: ToolSpec, issues: List<Issue>): Boolean {
        if (issues.isEmpty()) return false

        violationCount.computeIfAbsent(spec.expression) { AtomicLong() }.addAndGet(issues.size.toLong())
        val summary = issues.joinToString("; ") { "${it.path} ${it.message}" }
        logger.warn("Tool result violates its outputSchema | {} | {}", spec.expression, summary)

        return strictFailuresEnabled()
    }

    /** Violations counted for [spec] (used by tests and Phase 3 metrics). */
    fun violationCount(spec: ToolSpec): Long = violationCount[spec.expression]?.get() ?: 0L

    /** Total violations counted since start. */
    fun totalViolations(): Long = violationCount.values.sumOf { it.get() }

    /** Clears the counters (tests). */
    fun resetCounters() = violationCount.clear()

    // -------------------------------------------------------------------------
    // Minimal JSON Schema subset
    // -------------------------------------------------------------------------

    private fun validateAgainst(schema: JsonObject, value: JsonElement?, path: String): List<Issue> {
        val issues = mutableListOf<Issue>()
        val expectedType = (schema["type"] as? JsonPrimitive)?.contentOrNull()

        if (expectedType != null && !matchesType(expectedType, value)) {
            issues += Issue(path, "expected $expectedType but got ${describe(value)}")
            return issues
        }

        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value != null && allowed.none { it == value }) {
                issues += Issue(path, "value $value is not one of ${allowed.joinToString(", ")}")
            }
        }

        when (value) {
            is JsonObject -> {
                (schema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
                    ?.forEach { field ->
                        val present = value[field]
                        if (present == null || present is JsonNull) {
                            issues += Issue("$path.$field", "required field is missing")
                        }
                    }

                (schema["properties"] as? JsonObject)?.forEach { (field, fieldSchema) ->
                    val fieldValue = value[field] ?: return@forEach
                    if (fieldSchema is JsonObject) {
                        issues += validateAgainst(fieldSchema, fieldValue, "$path.$field")
                    }
                }
            }

            is JsonArray -> {
                (schema["items"] as? JsonObject)?.let { itemSchema ->
                    value.forEachIndexed { index, item ->
                        issues += validateAgainst(itemSchema, item, "$path[$index]")
                    }
                }
            }

            else -> Unit
        }

        return issues
    }

    private fun matchesType(expected: String, value: JsonElement?): Boolean = when (expected) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.contentOrNull() in setOf("true", "false")
        "integer" -> value is JsonPrimitive && !value.isString && value.contentOrNull()?.toLongOrNull() != null
        "number" -> value is JsonPrimitive && !value.isString && value.contentOrNull()?.toDoubleOrNull() != null
        "null" -> value == null || value is JsonNull
        else -> true
    }

    private fun describe(value: JsonElement?): String = when (value) {
        null, is JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> if (value.isString) "string" else "primitive"
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

    /** Whether `-Dmcp.validateResults=error` (tests) makes violations fail the call. */
    fun strictFailuresEnabled(): Boolean =
        System.getProperty("mcp.validateResults", "warn").equals("error", ignoreCase = true)
}
