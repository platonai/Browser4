package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.inference.chat.RoundTiming

/**
 * Where a coding/agent run spent its wall clock, carried on the task result (plan N3).
 *
 * The four buckets are the ones the efficiency work measured (docs-dev/copilot/
 * 20261113-agent-dev-efficiency-dev-plan.md):
 *
 * | bucket | what it is |
 * |---|---|
 * | [buildMs] | `coding.mvnBuild` / `coding.verify` tool bodies |
 * | [toolMs] | every other tool body |
 * | [callbackMs] | the tool loop's own per-call plumbing (observers, tracing, memory) |
 * | [modelMs] | the provider round-trips |
 *
 * They are accumulated in-process, so a caller can read them from `agent result`
 * instead of re-deriving them from the memory event log — the point of N3 is that the
 * measurement travels with the answer.
 *
 * [inputTokens] / [cachedInputTokens] / [outputTokens] come from the provider's own usage
 * report, summed over rounds. Output tokens are what model time is proportional to
 * (measured: ~0.6-0.9 s of fixed latency per request plus ~3.3-4 ms per output token),
 * and cached input is nearly free, which is why the split matters.
 */
data class RunTimings(
    val rounds: Int = 0,
    val buildMs: Long = 0,
    val toolMs: Long = 0,
    val callbackMs: Long = 0,
    val modelMs: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    /** The measured time (build + tools + callbacks + model), not the task's wall clock. */
    val totalMs: Long get() = buildMs + toolMs + callbackMs + modelMs

    /** JSON shape carried on the task result (`timings` field). */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "rounds" to rounds,
        "totalMs" to totalMs,
        "buildMs" to buildMs,
        "toolMs" to toolMs,
        "callbackMs" to callbackMs,
        "modelMs" to modelMs,
        "tokens" to linkedMapOf(
            "in" to inputTokens,
            "cachedIn" to cachedInputTokens,
            "out" to outputTokens,
        ),
    )
}

/**
 * Per-run accumulator behind [RunTimings].
 *
 * Deliberately not thread-safe: it is fed from the tool loop's own callbacks, which run on
 * the loop's single dispatch path, and read once when the run finishes.
 *
 * Tools are attributed in [onTool] (one call per executed tool, build tools split out);
 * rounds contribute model/callback time and tokens. Keeping the two sources for different
 * buckets is what makes the four buckets additive without double counting.
 */
class RunTimingsAccumulator {
    private var rounds = 0
    private var buildMs = 0L
    private var toolMs = 0L
    private var callbackMs = 0L
    private var modelMs = 0L
    private var inputTokens = 0L
    private var cachedInputTokens = 0L
    private var outputTokens = 0L

    /** One executed tool, with the duration the tool loop measured. */
    fun onTool(toolName: String, durationMs: Long) {
        val ms = durationMs.coerceAtLeast(0)
        if (isBuildTool(toolName)) buildMs += ms else toolMs += ms
    }

    /** One model round: its model/callback time and the provider's usage for it. */
    fun onRound(timing: RoundTiming) {
        rounds++
        modelMs += timing.modelMs.coerceAtLeast(0)
        callbackMs += timing.callbacksMs.coerceAtLeast(0)
        outputTokens += timing.outTokens.coerceAtLeast(0)
        // `inTokens` (when present) is the full prompt count; cachedIn is the part served
        // from the provider's cache. Older events do not carry the total, so fall back to
        // the cached count rather than inventing a number.
        inputTokens += timing.inTokens.coerceAtLeast(0)
        cachedInputTokens += timing.cachedInTokens.coerceAtLeast(0)
    }

    fun reset() {
        rounds = 0
        buildMs = 0
        toolMs = 0
        callbackMs = 0
        modelMs = 0
        inputTokens = 0
        cachedInputTokens = 0
        outputTokens = 0
    }

    fun snapshot(): RunTimings = RunTimings(
        rounds = rounds,
        buildMs = buildMs,
        toolMs = toolMs,
        callbackMs = callbackMs,
        modelMs = modelMs,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        outputTokens = outputTokens,
    )

    companion object {
        /**
         * Build entry points, in both spellings the loop can see: the MCP-style camelCase
         * name (`coding_mvnBuild`) and the dotted form (`coding.mvnBuild`).
         */
        private val BUILD_TOOL_NAMES = setOf(
            "coding_mvnBuild", "coding.mvnBuild",
            "coding_verify", "coding.verify",
        )

        fun isBuildTool(toolName: String): Boolean = toolName in BUILD_TOOL_NAMES
    }
}
