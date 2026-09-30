package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.inference.ToolExposeMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Which inner execution engine [RobustBrowserAgent] uses for a run.
 *
 * - [OBSERVE_ACT] — legacy observe→act loop (ARIA snapshot + JSON action
 *   description). DEPRECATED, kept for backward compatibility only.
 * - [CLI_TOOL_LOOP] — native function-calling loop driving browser4-cli
 *   subprocesses via `b4.run` (design v0.2). Default.
 */
enum class RunEngine {
    OBSERVE_ACT,
    CLI_TOOL_LOOP,
    ;

    companion object {
        /**
         * Parse from the `browser4.agent.runEngine` system property value.
         * The default is now [CLI_TOOL_LOOP]; the legacy engine can still be
         * selected with `observe-act` / `v1` / `legacy` — DEPRECATED, kept for
         * backward compatibility only.
         */
        fun parse(value: String?): RunEngine = when (value?.trim()?.lowercase()) {
            "observe-act", "observe_act", "v1", "legacy" -> OBSERVE_ACT
            else -> CLI_TOOL_LOOP
        }
    }
}

/**
 * Configuration for enhanced error handling, retry mechanisms and agent behavior.
 *
 * Each field tunes a specific aspect of the autonomous loop:
 * - maxSteps: Upper bound of observe->act iterations in a single resolve session.
 * - maxRetries: Retries for the high-level resolve() in case of transient/timeout errors.
 * - baseRetryDelayMs/maxRetryDelayMs: Exponential backoff parameters.
 * - consecutiveNoOpLimit: Abort after N consecutive steps without actionable tool calls.
 * - actionGenerationTimeoutMs / llmInferenceTimeoutMs: Timeouts for model inference.
 * - screenshotCaptureTimeoutMs / screenshotEveryNSteps: Screenshot cadence & timeout.
 * - memoryCleanupIntervalSteps / maxHistorySize: In-memory history retention & cleanup.
 * - enableAdaptiveDelays: Adds short delays based on average step execution time.
 * - enablePreActionValidation: Validates tool calls before execution for safety.
 * - actTimeoutMs / resolveTimeoutMs: Overall upper bound for act() and resolve().
 * - maxResultsToTry: Number of candidate actions to attempt per model generation in act().
 * - domSettleTimeoutMs / domSettleCheckIntervalMs: Stabilization of DOM before each step.
 * - allowLocalhost / allowedPorts: URL safety policy.
 * - maxSelectorLength / denyUnknownActions: Selector validation & unknown action policy.
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 30_000,
    /**
     * Abort after N consecutive steps without actionable tool calls.
     * Configurable via `-Dbrowser4.agent.noop.limit=<n>` — long coding
     * chains (compile-fix-test loops) benefit from a higher tolerance.
     */
    val consecutiveNoOpLimit: Int =
        System.getProperty("browser4.agent.noop.limit", "5").toInt().coerceAtLeast(1),
    val actionGenerationTimeoutMs: Long = 30_000,
    val screenshotCaptureTimeoutMs: Long = 5_000,
    val enableStructuredLogging: Boolean = false,
    val logInferenceToFile: Boolean = true,
    val enableDebugMode: Boolean = false,
    val enablePerformanceMetrics: Boolean = true,
    val maxHistorySize: Int = 100,
    val enableAdaptiveDelays: Boolean = true,
    val enablePreActionValidation: Boolean = true,
    // New configuration options for fixes
    val actTimeoutMs: Long = 10.minutes.inWholeMilliseconds,
    val llmInferenceTimeoutMs: Long = 10.minutes.inWholeMilliseconds,
    val maxResultsToTry: Int = 3,
    val domSettleTimeoutMs: Long = 5000,
    val domSettleCheckIntervalMs: Long = 100,
    val allowLocalhost: Boolean = false,
    val allowedPorts: Set<Int> = setOf(80, 443, 8080, 8443, 3000, 5000, 8000, 9000),
    val maxSelectorLength: Int = 1000,
    val denyUnknownActions: Boolean = false,
    // Tool exposure mode: TOOL_CALLING (default), CHAT, or TEXT
    val toolExposeMode: ToolExposeMode = ToolExposeMode.TOOL_CALLING,
    // ── Feedback-loop overhaul (docs-dev/copilot/browser4-agent-feedback-implementation-plan.md) ──
    /**
     * Wrap every tool result into a bounded [ai.platon.pulsar.agentic.model.ToolOutcome]
     * envelope before it is rendered back to the model (history + previous-step result).
     * Configurable via `-Dbrowser4.agent.toolOutcome=false` to revert to raw results.
     */
    val toolOutcome: Boolean =
        System.getProperty("browser4.agent.toolOutcome", "true").toBoolean(),
    /**
     * Tool disclosure strategy: `tiered` (task-adaptive L0/L1/L2) or `full` (flat list).
     * Configurable via `-Dbrowser4.agent.toolDisclosure=full`.
     */
    val toolDisclosure: String =
        System.getProperty("browser4.agent.toolDisclosure", "tiered"),
    /**
     * Abort the run after N consecutive text-only responses (no tool call, no completion).
     * 0 disables the fuse. Configurable via `-Dbrowser4.agent.textOnlyStallLimit=<n>`.
     */
    val textOnlyStallLimit: Int =
        System.getProperty("browser4.agent.textOnlyStallLimit", "5").toInt().coerceAtLeast(0),
    /**
     * Finish-report gate validation: `strict` (mismatched gates fail the task) or `warn`
     * (log only). Configurable via `-Dbrowser4.agent.finishGateCheck=warn`.
     */
    val finishGateCheck: String =
        System.getProperty("browser4.agent.finishGateCheck", "strict"),
    /**
     * Max model↔tool round-trips inside one native tool-calling loop turn.
     * Configurable via `-Dbrowser4.agent.toolLoop.maxIterations=<n>`.
     */
    val toolLoopMaxIterations: Int =
        System.getProperty("browser4.agent.toolLoop.maxIterations", "40").toInt().coerceAtLeast(1),
    /**
     * Inner execution engine for a run (design v0.2). Default is now
     * [RunEngine.CLI_TOOL_LOOP]; the legacy engine is still selectable via
     * `-Dbrowser4.agent.runEngine=observe-act` — DEPRECATED, kept for
     * backward compatibility only.
     */
    val runEngine: RunEngine =
        RunEngine.parse(System.getProperty("browser4.agent.runEngine", "cli")),
    // Overall timeout for resolve() to avoid indefinite hangs
    val resolveTimeoutMs: Long = 24.hours.inWholeMilliseconds,
    // Circuit breaker configuration
    val maxConsecutiveLLMFailures: Int = 5,
    val maxConsecutiveValidationFailures: Int = 8,
)
