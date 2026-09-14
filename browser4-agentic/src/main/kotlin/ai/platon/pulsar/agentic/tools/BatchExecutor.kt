package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Runs a list of tool calls as one request (requirement 12).
 *
 * MCP has no batch primitive, so a batch is a tool call whose steps are executed
 * by the **channel** and aggregated here — the parsing, the ordering policy, the
 * per-step envelope and the observability are identical on both channels, which is
 * what makes `POST /mcp` and `/mcp/call-tool` answer the same way.
 *
 * ## Ordering
 *
 * Steps run **serially by default**: browser work is stateful, and a batch that
 * clicks and then reads must observe the click. Parallel execution is opt-in
 * (`concurrency > 1`) and only allowed when *every* step is a read-only tool —
 * anything else is refused with an explicit error rather than silently reordered.
 *
 * ## Result shape
 *
 * ```json
 * {"steps": [{"index": 0, "id": "open", "tool": "navigate", "ok": true,
 *             "durationMs": 812, "text": "", "cached": false}],
 *  "failureCount": 0, "stoppedOnError": false, "cached": false,
 *  "cachedSteps": 0, "durationMs": 812}
 * ```
 *
 * `cached` is true only when every step was served from the result cache; a batch
 * with any state-changing step can never be, because such a step is never cached
 * (see [ToolResultCache]).
 *
 * @param stepRunner executes one step; supplied by the channel so this class stays
 *   free of sessions, drivers and dispatchers
 * @param readOnly whether a tool name is a read-only read; gates `concurrency`
 */
class BatchExecutor(
    private val stepRunner: SuspendStepRunner,
    private val readOnly: (toolName: String) -> Boolean = { false },
) {

    /** Executes one parsed step. */
    fun interface SuspendStepRunner {
        suspend fun run(step: BatchStep): BatchStepResult
    }

    /** One step of a batch, after parsing. */
    data class BatchStep(
        val index: Int,
        val id: String,
        val tool: String,
        val args: Map<String, Any?>,
    )

    /** What a step produced: the outcome, never an exception (that is [error]). */
    data class BatchStepResult(
        val ok: Boolean,
        val text: String? = null,
        val error: String? = null,
        val errorCode: String? = null,
        val cached: Boolean = false,
        /** Channel-specific fields kept verbatim (e.g. `snapshot`, `screenshot`). */
        val extras: Map<String, Any?> = emptyMap(),
    ) {
        companion object {
            fun failed(error: String?, errorCode: String? = null) =
                BatchStepResult(ok = false, error = error, errorCode = errorCode)
        }
    }

    /** One step's result, in the shared per-step envelope. */
    data class StepOutcome(
        val index: Int,
        val id: String,
        val tool: String,
        val ok: Boolean,
        val durationMs: Long,
        val text: String? = null,
        val errorCode: String? = null,
        val error: String? = null,
        val cached: Boolean = false,
        val extras: Map<String, Any?> = emptyMap(),
    ) {
        /** The map form both channels serialize. */
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "index" to index,
            "id" to id,
            "tool" to tool,
            "ok" to ok,
            "durationMs" to durationMs,
        ).apply {
            cached.takeIf { it }?.let { put("cached", true) }
            text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
            errorCode?.let { put("errorCode", it) }
            error?.let { put("error", it) }
            putAll(extras)
        }
    }

    /** The aggregated result of a batch. */
    data class BatchOutcome(
        val steps: List<StepOutcome>,
        val requestedSteps: Int,
        val bail: Boolean,
        val durationMs: Long,
    ) {
        val failureCount: Int get() = steps.count { !it.ok }
        val cachedSteps: Int get() = steps.count { it.cached }
        val stoppedOnError: Boolean get() = steps.size < requestedSteps
        val cached: Boolean get() = steps.isNotEmpty() && steps.all { it.cached }

        /** The map form both channels serialize. */
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "steps" to steps.map { it.toMap() },
            "failureCount" to failureCount,
            "stoppedOnError" to stoppedOnError,
            "cached" to cached,
            "cachedSteps" to cachedSteps,
            "durationMs" to durationMs,
        )
    }

    /**
     * Execute [steps], stopping after the first failure when [bail] is set.
     *
     * @param concurrency `1` (default) for the stateful serial path; `> 1` only for
     *   batches made entirely of read-only tools
     * @throws IllegalArgumentException when concurrency is requested for a batch
     *   that can change page state
     */
    suspend fun run(steps: List<BatchStep>, bail: Boolean = false, concurrency: Int = 1): BatchOutcome {
        require(steps.isNotEmpty()) { "A batch needs at least one step." }
        val parallel = concurrency > 1
        if (parallel) {
            val offending = steps.filterNot { readOnly(it.tool) }
            require(offending.isEmpty()) {
                "concurrency=$concurrency is only allowed for read-only batches; " +
                    "${offending.size} step(s) can change state (e.g. '${offending.first().tool}')"
            }
        }

        logger.info(
            "batch.run start steps={} bail={} concurrency={}",
            steps.size, bail, if (parallel) concurrency else 1,
        )
        val startedAt = System.nanoTime()
        val outcomes = if (parallel) runParallel(steps, concurrency) else runSerial(steps, bail)
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        val outcome = BatchOutcome(outcomes, steps.size, bail, durationMs)

        ToolMetrics.recordBatch(
            requested = steps.size,
            executed = outcome.steps.size,
            failures = outcome.failureCount,
            cached = outcome.cachedSteps,
            durationMs = durationMs,
        )
        logger.info(
            "batch.run done steps={} executed={} failures={} stoppedOnError={} cachedSteps={} durationMs={}",
            steps.size, outcome.steps.size, outcome.failureCount, outcome.stoppedOnError,
            outcome.cachedSteps, durationMs,
        )
        return outcome
    }

    private suspend fun runSerial(steps: List<BatchStep>, bail: Boolean): List<StepOutcome> {
        val outcomes = mutableListOf<StepOutcome>()
        for (step in steps) {
            val outcome = execute(step)
            outcomes += outcome
            if (!outcome.ok && bail) break
        }
        return outcomes
    }

    private suspend fun runParallel(steps: List<BatchStep>, concurrency: Int): List<StepOutcome> =
        withContext(Dispatchers.IO.limitedParallelism(concurrency.coerceIn(1, MAX_CONCURRENCY))) {
            steps.map { step -> async { execute(step) } }.awaitAll()
        }

    private suspend fun execute(step: BatchStep): StepOutcome {
        val startedAt = System.nanoTime()
        val result = try {
            stepRunner.run(step)
        } catch (e: Exception) {
            logger.warn("batch.step index={} tool={} failed: {}", step.index, step.tool, e.message)
            BatchStepResult.failed(e.message ?: "Unknown batch execution error", ToolErrorMapper.classify(e).wire)
        }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000

        logger.info(
            "batch.step index={} id={} tool={} ok={} cached={} durationMs={}",
            step.index, step.id, step.tool, result.ok, result.cached, durationMs,
        )
        return StepOutcome(
            index = step.index,
            id = step.id,
            tool = step.tool,
            ok = result.ok,
            durationMs = durationMs,
            text = result.text,
            errorCode = result.errorCode,
            error = result.error,
            cached = result.cached,
            extras = result.extras,
        )
    }

    /** Classify a thrown step failure the same way the channels do. */
    private fun ToolErrorCodeMapper(e: Exception): String = ToolErrorMapper.classify(e).wire

    companion object {
        private val logger = getLogger(BatchExecutor::class)

        /** Upper bound for the opt-in read-only parallel path. */
        const val MAX_CONCURRENCY = 4

        /**
         * Parse the `steps` array of a batch request.
         *
         * Accepted step shapes, so both the CLI (which sends `op`, `tool`,
         * `arguments`, plus focus hints) and a generic MCP client (which sends
         * `tool` + `args`) work:
         *
         * ```
         * {"tool": "navigate", "args": {"url": "…"}}          // canonical
         * {"tool": "navigate", "arguments": {"url": "…"}}     // CLI
         * {"op": "tool", "tool": "navigate", "arguments": {…}} // CLI, explicit op
         * ```
         *
         * @param rawSteps the value of the `steps` argument
         * @throws IllegalArgumentException when a step is not an object or has no tool
         */
        fun parseSteps(rawSteps: Any?): List<BatchStep> {
            val list = rawSteps as? List<*>
                ?: throw IllegalArgumentException("A batch requires a 'steps' array.")
            return list.mapIndexed { index, raw ->
                @Suppress("UNCHECKED_CAST")
                val map = raw as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Batch step at index $index must be an object.")

                val tool = (map["tool"] ?: map["name"])?.toString()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Batch step at index $index has no 'tool'.")
                val args = asAnyMap(map["args"] ?: map["arguments"]) ?: emptyMap()
                val id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: tool

                BatchStep(index = index, id = id, tool = tool, args = args)
            }
        }

        /** The `id` of a step used to accept an explicit id or fall back to the tool. */
        private fun asAnyMap(value: Any?): Map<String, Any?>? = when (value) {
            is Map<*, *> -> value.entries.associate { (key, v) -> key.toString() to v }
            else -> null
        }
    }
}
