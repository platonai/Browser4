package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.AgentTokenBudget
import ai.platon.pulsar.agentic.inference.RequestTokenLimitExceededException
import ai.platon.pulsar.agentic.inference.TokenBudgetExceededException
import ai.platon.pulsar.agentic.inference.chat.*
import ai.platon.pulsar.agentic.inference.detail.*
import ai.platon.pulsar.agentic.memory.AgentMemory
import ai.platon.pulsar.agentic.memory.MemoryConfig
import ai.platon.pulsar.agentic.memory.MemoryScope
import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.agentic.memory.MemoryToolTarget
import ai.platon.pulsar.agentic.memory.Sanitizer
import ai.platon.pulsar.agentic.memory.external.ExternalMemoryConfig
import ai.platon.pulsar.agentic.memory.external.MemoryExternalToolExecutor
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.TaskCompletion
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.CodingWorkspace
import ai.platon.pulsar.coding.SafeText
import ai.platon.pulsar.coding.VerificationReport
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.*
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

// File-level constants previously in companion object
private const val COMPACT_INLINE_SESSION_LENGTH = 160
private const val COMPACT_INLINE_INSTRUCTION_LENGTH = 100
private const val CLI_CONTINUE_NUDGE =
    "If the task is fully finished, call system.taskComplete with the final report now. " +
        "Otherwise continue working with tools. Do not answer in plain text unless you are done."
/** How often the CLI tool loop emits a liveness event (every N tool executions). */
private const val CLI_TOOL_PROGRESS_EVENT_INTERVAL = 5

/**
 * Framing of a user steering message injected mid-run.
 *
 * The model sees a steady stream of tool results and nudges; an unframed
 * sentence could be mistaken for another automatic nudge. The header states the
 * authority of the message explicitly, so a mid-round injection is obeyed
 * instead of being treated as commentary.
 */
private const val CLI_STEERING_PREFIX =
    "[User steering — the user sent this while the task is running. Apply it from now on; " +
        "it overrides the earlier plan.]"
/** Explicit tool domains exposed to the CLI engine (design v0.2 §4.4; memory §7.1). */
private val CLI_ENGINE_DOMAINS = setOf("coding", "b4", "system", "memory")

/** Whole domains kept in the CLI engine's INITIAL tool set. */
private val CLI_CORE_DOMAINS = setOf("b4", "system", "memory")

/**
 * Curated coding core exposed initially for CODING tasks (progressive
 * disclosure): the daily drivers. The long tail (symbols/references,
 * kt-symbols/kt-references, scaffold family, impact, runCode, lspServers,
 * tokenStats, ...) is reachable via system.listTools / system.exposeTools.
 *
 * `readLines`, `verify`, `mvnBuild` and `diagnostics` belong here because the
 * coding contract itself names them: the system prompt tells the model to
 * `coding.verify` before finishing, and a refactor of a large file is done with
 * bounded `coding.readLines` reads. Leaving them out made a live run spend its
 * first rounds on `system.listTools` / `system.exposeTools` to discover tools
 * the prompt had just told it to use.
 */
private val CLI_CORE_CODING_METHODS = setOf(
    "shell", "shellOutput", "shellStatus",
    "read", "readLines", "write", "append", "replace", "insertAfter", "editLines", "delete", "mkdir",
    "listDir", "glob", "grep", "stat", "diff", "changeSummary",
    "validate", "verify", "mvnBuild", "diagnostics", "workspaceRoot", "devTask",
)

/**
 * Coding-domain methods exposed initially for BROWSING tasks: the most common
 * FILE tools only (helper scripts for eval, small data files, locating
 * things), plus the three read-only bookkeeping tools the browsing prompt
 * names by name — `coding.changeSummary` ("it lists exactly the files this run
 * wrote" for `filesChanged`), `coding.diff`, and `coding.workspaceRoot` (the
 * model must be able to answer "where am I?"). Leaving `changeSummary` out of
 * the initial set forced a live run to spend a `system.listTools` /
 * `system.exposeTools` round on a tool the prompt had just told it to use.
 *
 * `shell` belongs here for the same reason, and a second live run proved it
 * (Amazon top-20, 2026-10-28): the browsing prompt tells the model to extract an
 * `.xlsx` task input "with a script (`coding.shell` + python/openpyxl where
 * available)", yet the first request advertised 28 tools without `coding_shell`.
 * The model called it anyway and a permissive provider executed it, but a strict
 * function-calling provider cannot call an undeclared tool, and the run paid a
 * `system.listTools` round for a tool its own prompt had named.
 *
 * The rest of the coding long tail — shellOutput/shellStatus, mvnBuild, validate,
 * devTask, editLines/insertAfter, symbols, kt-symbols, scaffold family, impact,
 * ... — stays hidden and is reachable via system.listTools / system.exposeTools
 * on demand.
 */
private val CLI_BROWSING_CODING_METHODS = setOf(
    "shell",
    "read", "write", "append", "replace",
    "listDir", "glob", "grep",
    "stat", "mkdir", "delete",
    "diff", "changeSummary", "workspaceRoot",
)

/**
 * System methods the CLI engine's contracts depend on — always present in the
 * INITIAL tool set regardless of `browser4.agent.toolLoop.initialToolSet`:
 * `system.taskComplete` is the engine's only completion signal, and the CLI
 * system prompt instructs the model to load the bundled SKILL.md via
 * `system.skillDoc`.
 */
private val CLI_CONTRACT_SYSTEM_METHODS = setOf("taskComplete", "skillDoc")

/**
 * The CLI engine's tool set, derived from the full reverse registry plus the
 * authoritative system-domain specs.
 *
 * The hardcoded `TOOL_CALL_SPECIFICATION` advertises only `system.help`, so
 * the registry alone would never carry `system.taskComplete` /
 * `system.skillDoc` — the tools the CLI engine's completion and SKILL.md
 * contracts depend on. Those come from [SystemToolExecutor.getToolSpecs] and
 * are force-included in the initial set whatever [initialToolSet] says
 * (strict function-calling models only call declared tools).
 *
 * Pure logic — no IO — so it is unit-testable without an agent session.
 */
internal data class CliEngineToolSet(
    /** Reverse registry (tool name → ToolSpec) for the execution coordinator. */
    val registry: Map<String, ToolSpec>,
    /** Full CLI-domain specifications (disclosure registry / prompt tracing). */
    val specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** The curated initial set actually sent to the model. */
    val initialSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** Disclosure registry; non-empty enables system.listTools / exposeTools. */
    val disclosureSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** The mode the initial selection actually used (after fallback). */
    val usedMode: String,
)

/**
 * Build the CLI engine's tool set.
 *
 * Task-adaptive initial exposure: browsing tasks (default) get the web-access
 * tools (`b4.*`), the system contract tools and the most common file tools;
 * coding tasks additionally get the full curated coding core (shell, build,
 * validate, devTask, ...). The long tail is always reachable via
 * `system.listTools` / `system.exposeTools`.
 *
 * @param allRegistry The full reverse registry (tool name → ToolSpec) from
 *   [AgentToolManager.getLangChain4jToolRegistry].
 * @param systemSpecs The authoritative system-domain specs from
 *   [SystemToolExecutor.getToolSpecs] (help/skillDoc/taskComplete).
 * @param codingMode True when the task was explicitly marked as a coding task
 *   (set by the task runner from `command.run`'s `coding` argument before the run).
 * @param coreMethodAllowlist Explicit per-domain method allowlist; when null
 *   the task-adaptive allowlist is used ([CLI_BROWSING_CODING_METHODS] for
 *   browsing tasks, [CLI_CORE_CODING_METHODS] for coding tasks).
 * @param policy The access policy that decides which methods exist at all. Methods the policy
 *   denies are removed from the registry *before* the initial selection and before the disclosure
 *   pool is built, so `system.listTools` / `system.exposeTools` cannot bring them back — the
 *   initial exposure and the disclosure pool are two views of one filtered list. Under the default
 *   [AgentAccessPolicy.serverDefault] (`FULL`) nothing is filtered.
 */
internal fun buildCliEngineToolSet(
    allRegistry: Map<String, ToolSpec>,
    systemSpecs: List<ToolSpec>,
    initialToolSet: String,
    disclosureEnabled: Boolean,
    codingMode: Boolean = false,
    coreDomains: Set<String> = CLI_CORE_DOMAINS,
    coreMethodAllowlist: Map<String, Set<String>>? = null,
    policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
): CliEngineToolSet {
    // Task-adaptive coding allowlist; explicit allowlists (callers/tests) win.
    val effectiveAllowlist = coreMethodAllowlist
        ?: if (codingMode) mapOf("coding" to CLI_CORE_CODING_METHODS)
        else mapOf("coding" to CLI_BROWSING_CODING_METHODS)

    // CLI-domain specs: the registry minus the hardcoded system section
    // (only system.help), plus the executor's authoritative system specs
    // (help/skillDoc/taskComplete). Duplicates collapse on the sanitized
    // tool name, keeping the first occurrence (the registry's merged help).
    //
    // The access policy is applied here, on the single list every later view is
    // derived from: the initial set, the disclosure registry and the `listTools`
    // listing. Filtering only the initial selection would leave `exposeTools`
    // able to re-enable a denied method (plan P0-2/E4).
    //
    // `system.*` is outside the filter: the policy governs the coding surface, and a
    // `denyTools=["*"]` that removed `system.taskComplete` would leave the run with no way to
    // finish at all (the model would report completion by prose, which the finish gate rejects).
    val cliDomainSpecs = (
        allRegistry.values
            .filter { it.domain in CLI_ENGINE_DOMAINS && it.domain != "system" }
            .filter { policy.allowsTool(it.domain, it.method) } +
            systemSpecs
        )
        .distinctBy { ToolSpecificationConverter.toolName(it.domain, it.method) }
    val registry = ToolSpecificationConverter.toRegistry(cliDomainSpecs)
    val specs = ToolSpecificationConverter.toToolSpecifications(cliDomainSpecs)

    if (!disclosureEnabled) {
        return CliEngineToolSet(registry, specs, specs, emptyList(), initialToolSet)
    }

    var selected = ToolDisclosureTools.selectInitialSpecs(cliDomainSpecs, initialToolSet, coreDomains, effectiveAllowlist)
    var usedMode = initialToolSet
    if (selected.isEmpty()) {
        // Misconfigured pattern list (non-CLI domains, or tokens that
        // matched nothing): degrade to the default core set instead of
        // leaving the model with no initial tools beyond the meta pair.
        selected = ToolDisclosureTools.selectInitialSpecs(cliDomainSpecs, "core", coreDomains, effectiveAllowlist)
        usedMode = "core"
    }

    // The completion + skill-doc contract survives every initialToolSet mode.
    val contractSpecs = systemSpecs
        .filter { it.method in CLI_CONTRACT_SYSTEM_METHODS }
        .filter { s -> selected.none { it.domain == s.domain && it.method == s.method } }
    val initialSpecs = ToolSpecificationConverter.toToolSpecifications(contractSpecs + selected)

    val disclosureSpecs = if (initialSpecs.size < specs.size) specs else emptyList()
    return CliEngineToolSet(registry, specs, initialSpecs, disclosureSpecs, usedMode)
}

// ─── Per-round tool digest (P1-2) ──────────────────────────────────────────

/** How many newest executed tools the per-round digest summarizes. */
private const val ROUND_DIGEST_RESULTS = 6

/** Hard cap on the whole per-round digest. */
private const val ROUND_DIGEST_MAX_CHARS = 2_000

/** One executed tool inside a CLI-engine round, for the continuation digest. */
internal data class RoundToolRecord(
    val name: String,
    val resultText: String,
)

/**
 * Inner tool-loop round cap.
 *
 * A coding chain (read → grep → readLines → replace → build → test) is far longer
 * than a browsing one, and every overflow restarts the round from a *lossy* digest
 * (tool names + the newest few results, ~2K chars) — the model's own plan is gone.
 * A live refactor run hit the 40-call cap three times in a row without landing a
 * single edit, re-mapping the same 4,000-line class each round. Coding rounds
 * therefore get a higher floor; the outer loop still bounds the whole run.
 */
internal fun cliToolLoopMaxIterations(configured: Int, codingMode: Boolean): Int =
    configured.coerceAtLeast(if (codingMode) 60 else 40)

/**
 * The "progress so far" block appended to an overflow continuation.
 *
 * The block must survive being shipped in every continuation, so it is bounded by
 * line count and characters; a blank summary becomes an explicit instruction to
 * persist instead of an empty section the model can ignore.
 *
 * @param summary `coding.changeSummary()` output (null when unavailable)
 */
internal fun progressDigestFromSummary(summary: String?, maxLines: Int = 20, maxChars: Int = 1_200): String {
    val text = summary?.trim().orEmpty()
    if (text.isEmpty()) {
        return "(no files written yet — persist collected rows before continuing)"
    }
    return text.lineSequence()
        .filter { it.isNotBlank() }
        .take(maxLines)
        .joinToString("\n")
        .take(maxChars)
}

/**
 * True when this tool-result text reports a failure.
 *
 * Two shapes reach the model: the coordinator's raw `[fail] …` catch-all, and the
 * bounded [ai.platon.pulsar.agentic.model.ToolOutcome] envelope, whose header is
 * `domain.method [fail] summary`. Checking only the raw prefix (the old code) missed
 * every envelope failure: a policy refusal was rendered `[fail]` for the model yet
 * still counted as ok here, so `agent run --follow` never printed it (observed in
 * the 2026-10-28 supervision run, where all 20 refusals stayed invisible).
 */
internal fun isFailedToolResult(resultText: String): Boolean {
    val trimmed = resultText.trimStart()
    if (trimmed.startsWith("[fail]")) return true
    val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
    return firstLine.contains(" [fail] ")
}

/** Consecutive rounds without a recorded file mutation before a coding run is told to land an edit. */
internal const val CLI_CODING_READONLY_ROUND_LIMIT = 2

/**
 * Directive injected into a coding run that keeps reading without editing.
 *
 * Observed live: three refactor runs spent 100-240 tool calls building outlines,
 * member maps and reference indexes across several rounds and never changed a
 * file. The prompt already asked for the first edit "as soon as you know the
 * shape of the code"; the model ignored it. A no-mutation round counter is the
 * deterministic version of that instruction: after [limit] consecutive rounds
 * that recorded no file mutation, the next round is told, in the user turn, to
 * land the edit (or name the blocker) — and the counter restarts, so the nudge is
 * not spam.
 *
 * Progress is read from `CodingAgentFileSystem.mutationsSnapshot()` — the same
 * source of truth the finish gate uses — not from per-tool bookkeeping: a round
 * that overflows the inner loop never surfaces its tool records here (measured:
 * the record-based counter stayed at 0 through three overflowed rounds).
 *
 * @return the directive text, or null while the run is still within budget.
 */
internal fun codingProgressNudge(readOnlyRounds: Int, limit: Int = CLI_CODING_READONLY_ROUND_LIMIT): String? {
    if (readOnlyRounds < limit) return null
    return "You have spent $readOnlyRounds consecutive rounds reading and mapping code without changing a file. " +
        "Stop surveying: make the first concrete edit now (coding.replace / coding.write / coding.editLines), " +
        "then run coding.verify on the module. If something genuinely blocks the edit, state exactly what it is."
}

/**
 * Bounded digest of what ONE outer round actually executed, fed back to the
 * model in the next round's continuation message.
 *
 * The inner [AgentToolCallLoop] builds its own (local) message list, so tool
 * calls and results die with the round; without this digest the model would
 * re-execute tools it already ran. Keeps the newest [ROUND_DIGEST_RESULTS]
 * records as head+tail excerpts ([ToolResultDigest]) within
 * [ROUND_DIGEST_MAX_CHARS] — first-line-only entries reduced every
 * `coding.shell` result to `Session: devshell-N`.
 *
 * Pure logic — no IO — so it is unit-testable without an agent session.
 */
internal fun cliRoundToolDigest(records: List<RoundToolRecord>): String? {
    if (records.isEmpty()) return null
    val entries = records.takeLast(ROUND_DIGEST_RESULTS).map { it.name to it.resultText }
    val digest = ToolResultDigest.digest(entries, ROUND_DIGEST_MAX_CHARS)
    if (digest.isEmpty()) return null
    return "This round executed ${records.size} tool(s): $digest"
}

open class RobustBrowserAgent(
    session: AgenticSession, val maxSteps: Int = 100, config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BasicBrowserAgent(session, config) {
    private val logger = getLogger(RobustBrowserAgent::class)
    private val slogger = StructuredAgentLogger(logger, config)

    /**
     * Per-task override for [AgentConfig.consecutiveNoOpLimit], set by
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] when the caller
     * passes a noop limit with the task (e.g. `agent run --noop-limit 10`). Long coding
     * chains (compile-fix-test loops) benefit from a higher tolerance than the default.
     */
    @Volatile
    var noopLimitOverride: Int? = null

    /**
     * Per-task token budget, set by the task runner when the caller passes
     * `command.run(tokenBudget=…)` / `agent run --token-budget <n>`.
     *
     * It wins over both the configured `agent.token.budget.total` and the
     * process-wide [ai.platon.pulsar.agentic.inference.RuntimeConfigRegistry]
     * override, and is deliberately NOT written into the registry: a task-level
     * choice must not leak into every later task in the JVM (the runner clears this
     * field in its `finally`). `0` means unlimited, matching
     * [ai.platon.pulsar.agentic.inference.AgentTokenBudget]'s semantics.
     */
    @Volatile
    var tokenBudgetOverride: Long? = null

    /**
     * Effective token budget of the most recent run (`null` before the first run and
     * for a run created without a budget).
     *
     * Published so the task runner can report it on a terminal (especially failed)
     * status: a supervisor needs to see "aborted at 5.0M / 5.0M", not just
     * `TokenBudgetExceededException`.
     */
    @Volatile
    var lastRunTokenBudget: Long? = null
        private set

    /**
     * Per-agent scratch workspace for CLI-engine (browser) tasks. Helper files
     * the model writes via coding.* and b4.run land here — never the backend
     * working directory / repository root (workspace isolation, P1).
     *
     * Respects [workspaceDirOverride] (set by the runner for `agent run
     * --fresh-workspace`); otherwise falls back to `<baseDir>/workspace`.
     */
    internal val agentWorkspaceDir: Path
        get() = workspaceDirOverride ?: defaultWorkspaceDir

    private val defaultWorkspaceDir: Path by lazy {
        baseDir.resolve("workspace").also { Files.createDirectories(it) }
    }

    /**
     * Runtime override of the agent workspace directory, set by the task runner
     * when the caller requests a fresh, isolated workspace (`agent run
     * --fresh-workspace`). Cleared in the runner's `finally`.
     */
    @Volatile
    var workspaceDirOverride: Path? = null

    /**
     * Live step sink of the current run, set by the task runner
     * ([ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner]).
     *
     * Every model round, tool call and tool result of the CLI engine is reported
     * here, which is what `agent run --follow` and the interactive REPL render.
     * `null` (no observer) costs one branch per step. The runner clears it in
     * its `finally`, like every other per-task override: the companion agent is
     * shared by all tasks of a session.
     */
    @Volatile
    var runEventListener: ((AgentRunEvent) -> Unit)? = null

    /**
     * Drain for the current run's user steering messages, set by the task
     * runner. Whatever the user typed into the interactive session while the
     * task was running is injected as a user message at the next model
     * round-trip (see [drainSteering]).
     *
     * A lambda rather than the queue itself keeps ownership in the runner, which
     * also removes the queue when the task ends.
     */
    @Volatile
    var steeringSource: (() -> List<String>)? = null

    /**
     * Test/runtime override of the agent memory root directory (defaults to
     * `<APP_DATA>/memory`). Lets tests isolate the memory files, and lets
     * multi-tenant deployments relocate them.
     */
    @Volatile
    internal var agentMemoryRootDirOverride: Path? = null

    /**
     * Generic agent memory (design: robust-browser-agent-memory-system-design.md):
     * L0 event log + query + scratchpad + run-start recall, wired into the CLI
     * engine loop below. Lazy: agents that never run the CLI loop pay nothing.
     */
    internal val agentMemory: AgentMemory by lazy {
        val memory = AgentMemory(
            MemoryScope(agentUuid = uuid.toString()),
            rootDir = agentMemoryRootDirOverride ?: AgentMemory.defaultRootDir(),
        )
        if (MemoryConfig.enabled) {
            // memory.* tools: the executor is stateless, so one global
            // registration suffices; per-agent dispatch binds the target.
            if (CustomToolRegistry.instance.get("memory") == null) {
                CustomToolRegistry.instance.register(
                    MemoryToolExecutor(),
                    MemoryToolExecutor().getToolSpecs().values.toList(),
                )
            }
            agentToolManager.registerCustomTarget("memory", MemoryToolTarget(memory))

            // L2 external memory bridge (M4): wait for the discovery handshake
            // (bounded), then register the discovered tools so the model can
            // call them through the normal tool loop.
            memory.externalBridge?.let { bridge ->
                val external = ExternalMemoryConfig.fromSystem()
                runBlocking { bridge.awaitConnected(external.connectTimeoutMs) }
                if (bridge.getToolSpecs().isNotEmpty()) {
                    if (CustomToolRegistry.instance.get(external.toolPrefix) == null) {
                        val executor = MemoryExternalToolExecutor(bridge, external.toolPrefix)
                        CustomToolRegistry.instance.register(
                            executor, executor.getToolSpecs().values.toList(),
                        )
                    }
                    agentToolManager.registerCustomTarget(external.toolPrefix, bridge)
                } else {
                    logger.warn(
                        "External memory bridge connected with no tools; registration skipped"
                    )
                }
            }
        }
        memoryInitialized.set(true)
        memory
    }

    internal val isAgentMemoryInitialized: Boolean get() = memoryInitialized.get()

    private val memoryInitialized = AtomicBoolean(false)

    /**
     * Start time of the most recent CLI-engine run, for memory completion
     * duration accounting ([AgentMemorySink.completed]).
     */
    @Volatile
    private var cliRunStartedAt: Instant? = null

    /**
     * Complete-prompt + per-tool run tracing for the CLI engine. Writes into
     * `<APP_DATA_DIR>/logs/agent/<start-time>/<agent-uuid>/` (typically
     * `~/.browser4/logs/agent/...`; see [AgentPaths]); in development a
     * `logs/agent` symlink at the project root points there:
     * - `cli-prompt/<ts>.<seq>.request.json` — the EXACT message list plus the
     *   tool specifications sent to the model on every round-trip;
     * - `cli-tool-trace.jsonl` — every executed tool with arguments, full
     *   result text and duration;
     * - `cli-events.jsonl` — run-level events (start, overflow, completion).
     * Gated by [AgentConfig.logInferenceToFile] (default true).
     */
    private val cliLoopTracer by lazy {
        CliLoopTracer(uuid, startTime, config.logInferenceToFile, cliViewToolNames(session.sessionConfig))
    }

    private val noopLimit: Int get() = (noopLimitOverride ?: config.consecutiveNoOpLimit).coerceAtLeast(1)

    /**
     * Repair-round budget for this agent (plan P1.3). Driven by the latest
     * `coding.verify` verdict observed in the tool results: a failing verdict
     * spends one round, the diagnostics go back to the model as a user message,
     * and an exhausted budget fails the task with the verdict as evidence.
     */
    private val repairLoop = RepairLoop(config.repairMaxRounds)

    /**
     * Repair/amend policy for a completion report that omits files the run
     * really wrote (rule 7 of [FinishReportGate]).
     *
     * Uses the same round budget as [repairLoop] but its own counter: a
     * bookkeeping omission must not eat the `coding.verify` repair budget.
     * `-Dbrowser4.agent.finish.unlistedFiles=reject` restores fail-fast.
     */
    private val finishReportRepair = FinishReportRepair(
        maxRounds = config.repairMaxRounds,
        amendUnlistedFiles = !config.finishUnlistedFiles.equals("reject", ignoreCase = true),
    )

    /**
     * Latest structured verification verdict observed in the tool results of
     * the current run (null until `coding.verify` has run). Reset per run.
     */
    @Volatile
    private var lastVerificationVerdict: VerificationReport? = null

    /**
     * Coding mode override (see [BasicBrowserAgent.codingMode]): set by
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] from the
     * task's explicit coding flag (`command.run`'s `coding` argument, the CLI's
     * `agent run --coding`). Never auto-detected. Skips the search-engine
     * navigation and screenshots.
     */
    @Volatile
    override var codingMode: Boolean = false

    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    // A dedicated scope for all agent work so close() can cancel promptly
    private val agentJob = SupervisorJob()
    private val agentScope = CoroutineScope(Dispatchers.Default + agentJob)

    // New components for better separation of concerns
    private val circuitBreaker = CircuitBreaker(
        maxLLMFailures = config.maxConsecutiveLLMFailures,
        maxValidationFailures = config.maxConsecutiveValidationFailures,
        maxExecutionFailures = 3
    )
    private val retryStrategy = RetryStrategy(
        maxRetries = config.maxRetries, baseDelayMs = config.baseRetryDelayMs, maxDelayMs = config.maxRetryDelayMs
    )
    private val retryCounter = AtomicInteger(0)

    private val transcriptPersister = TranscriptPersister(
        stateManager = stateManager,
        stateHistory = stateHistory,
        slogger = slogger,
        agentUuid = uuid,
        circuitBreaker = circuitBreaker,
        retryCounter = retryCounter,
    )

    /**
     * High-level problem resolution entry. Builds an ActionOptions and delegates to resolve(ActionOptions).
     */
    override suspend fun run(task: String): AgentHistory {
        val opts = ActionOptions(action = task)
        return run(opts)
    }

    /**
     * Run an autonomous loop (observe -> act -> ...) attempting to fulfill the user goal described
     * in the ActionOptions. Applies retry and timeout strategies; records structured traces but keeps
     * stateHistory focused on executed tool actions only.
     *
     * @param action The action options containing the user's goal and configuration
     * @return The history of THIS run: a detached snapshot scoped by the run's execution session,
     * so callers never see other runs' states and later trims of the shared history cannot
     * mutate this result.
     * @throws CancellationException if the agent is closed or the operation is canceled
     */
    override suspend fun run(action: ActionOptions): AgentHistory {
        _lastRunSessionId = null
        resetInnerToolExecutions()
        onWillRun(action)

        // Track the session id directly from the resolve result instead of
        // indexing into the shared contexts list (which may be trimmed in the
        // future, and could leak the previous run's session id on early failure).
        var runSessionId: String? = null

        try {
            val ctx = agentScope.coroutineContext.minusKey(Job)

            val result = withContext(ctx) { resolveProblemInCoroutine(action) }
            runSessionId = result.context.sessionId

            onDidRun(action, result.result)

            // Surface abnormal terminations (no-op limit, max steps, exhausted
            // retries) to the caller instead of returning a history that LOOKS
            // successful. Runners (e.g. StatefulAgentRunner) mark the task
            // failed when the exception propagates — without this, MAX_STEPS
            // aborts were reported as "completed" with status 200.
            result.result.exception?.let { throw it }
        } catch (e: CancellationException) {
            logger.info("🛑 run.cancelled reason={}", e.message ?: "user cancellation")
            throw e
        } finally {
            stateManager.writeAllProcessTrace()
            _lastRunSessionId = runSessionId
        }

        return stateHistory.snapshotFor(_lastRunSessionId)
    }

    /**
     * Executes a single observe->act cycle for a supplied ActionOptions. Times out after actTimeoutMs
     * to prevent indefinite hangs. Model may produce multiple candidate tool calls internally; only
     * one successful execution is recorded in stateHistory.
     *
     * @param action The action to execute
     * @return Result of the action execution
     */
    override suspend fun act(action: ActionOptions): ActResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.act(action)
            }
        } catch (e: CancellationException) {
            logger.info("🛑 act.cancelled action={}", action.action.take(50))
            ActResultHelper.failed(e, action.action)
        }
    }

    /**
     * Executes a tool call derived from a prior observation result. Performs patching (selector/url),
     * validation, and updates AgentState history on success or failure.
     *
     * @param observe The observation result containing the action to execute
     * @return Result of the action execution
     */
    @Deprecated("Use the CLI tool-loop engine path instead")
    override suspend fun act(observe: ObserveResult): ActResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.act(observe)
            }
        } catch (e: CancellationException) {
            logger.info("🛑 act.cancelled instruction={}", observe.agentState.instruction.take(50))
            ActResultHelper.failed(e, action = observe.actionDescription?.instruction ?: "")
        }
    }

    /**
     * Structured extraction: builds a rich prompt with DOM snapshot & optional JSON schema; performs
     * two-stage LLM calls (extract + metadata) and merges results with token/time metrics.
     *
     * @param options Extraction options including schema and target elements
     * @return Extraction result with structured data
     */
    override suspend fun extract(options: ExtractOptions): ExtractResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.extract(options)
            }
        } catch (e: CancellationException) {
            logger.info(
                "🛑 extract.cancelled instruction={}", options.instruction.take(50)
            )
            ExtractResult(
                success = false,
                message = "USER interrupted: ${e.message}",
                data = JsonNodeFactory.instance.objectNode(),
                completed = false
            )
        }
    }

    /**
     * Observes the page given an instruction, returning zero or more ObserveResult objects describing
     * candidate elements and potential actions (if returnAction=true).
     */
    @Deprecated("Use the CLI tool-loop engine path instead")
    override suspend fun observe(options: ObserveOptions): List<ObserveResult> {
        val context = stateManager.getOrCreateActiveContext(options, "observe")

        val ctx = agentScope.coroutineContext.minusKey(Job)

        if (!options.fromResolve) {
            return withContext(ctx) {
                super.observe(options)
            }
        }

        try {
            val result = withContext(ctx) {
                onWillObserve(options)
                doObserve(options)
            }

            circuitBreaker.recordSuccess(CircuitBreaker.FailureType.LLM_FAILURE)

            onDidObserve(options, result)

            return result.observeResults
        } catch (e: Exception) {
            handleObserveException(e, context)
            return emptyList()
        }
    }

    fun stop() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Record close event before cancelling operations to ensure it's captured
            runCatching {
                val last = stateHistory.states.lastOrNull()
                stateManager.addTrace(last, event = "userClose", message = "🛑 USER CLOSE")
            }.onFailure { logger.warn("Failed to record close trace: ${it.message}") }

            // Cancel agent job - this will propagate cancellation to all child jobs
            runCatching {
                agentJob.cancel(CancellationException("USER interrupted via close()"))
            }.onFailure {
                logger.warn("Agent job cancellation error: ${it.message}")
            }

            // Cancel any background CLI jobs (long crawl/swarm processes) so the
            // agent never leaves orphan subprocesses behind.
            if (isAgentToolManagerInitialized) {
                runCatching { agentToolManager.closeCliJobs() }
                    .onFailure { logger.warn("Failed to close CLI jobs: ${it.message}") }
            }

            // Close bound WebDriver if exists
            runCatching {
                session.boundDriver?.let { driver ->
                    driver.close()
                    session.unbindDriver(driver)
                }
            }.onFailure { logger.warn("Failed to close bound WebDriver: ${it.message}") }

            // Close the agent memory (event log buffers + search index).
            if (isAgentMemoryInitialized) {
                runCatching { agentMemory.close() }
                    .onFailure { logger.warn("Failed to close agent memory: ${it.message}") }
            }
        }
    }

    /**
     * Returns a concise summary of the latest agent state; if no history exists, returns a placeholder text.
     */
    override fun toString(): String {
        return stateHistory.states.lastOrNull()?.toString() ?: "(no history)"
    }

    // ─── Resolution pipeline ──────────────────────────────────────────────────

    private suspend fun resolveProblemInCoroutine(action: ActionOptions): ResolveResult {
        val instruction = action.action
        val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
        val sessionStartTime = baseContext.stepStartTime

        // Add start history for better traceability (meta record only)
        stateManager.addTrace(
            baseContext.agentState, event = "resolveStart", items = mapOf(
                "session" to baseContext.sid,
                "goal" to Strings.compactInline(instruction, COMPACT_INLINE_SESSION_LENGTH),
                "maxSteps" to config.maxSteps,
                "maxRetries" to config.maxRetries
            ), message = "🚀 resolve START"
        )

        // Overall timeout to prevent indefinite hangs for a full resolve session
        // Use the deterministic worst-case delay budget: maxRetries * maxDelayMs.
        // (calculateRetryDelay has random jitter, which would make the timeout
        // budget non-deterministic across runs.)
        val maxPossibleDelays = config.maxRetries * config.maxRetryDelayMs
        val effectiveTimeout = config.resolveTimeoutMs + maxPossibleDelays

        return try {
            val result = withTimeout(effectiveTimeout.milliseconds) {
                resolveProblemWithRetry(action, baseContext)
            }

            val dur = Duration.between(sessionStartTime, Instant.now()).toMillis()
            stateManager.addTrace(
                result.context.agentState, event = "resolveDone", items = mapOf(
                    "session" to baseContext.sid, "success" to result.result.isSuccess, "durationMs" to dur
                ), message = "✅ resolve DONE"
            )

            result
        } catch (e: TimeoutCancellationException) {
            stateManager.addTrace(
                baseContext.agentState, event = "resolveTimeout", items = mapOf(
                    "timeoutMs" to effectiveTimeout,
                    "instruction" to Strings.compactInline(instruction, COMPACT_INLINE_SESSION_LENGTH)
                ), message = "⏳ resolve TIMEOUT"
            )
            // Wrap in a non-cancellation error: a resolve timeout is NOT a user
            // cancellation. run() rethrows the ActResult exception, and a raw
            // TimeoutCancellationException would route through the cancellation
            // channel and be misreported as "cancelled" by callers.
            val timeoutError = PerceptiveAgentError.TimeoutError(
                "⏳ Resolve timeout after $effectiveTimeout ms", e
            )
            val actResult = ActResultHelper.failed(timeoutError, action = instruction)
            ResolveResult(baseContext, actResult)
        } finally {
            // clear history so the next task will have a clean operation trace for summary.
            // but we do not clear process trace which will be kept to trace all operations and states.
            // 20251122: DO NOT CLEAR HISTORY, is you want to run new task with a new context, use TaskScopedBrowserPerceptiveAgent
            // instead.
            // stateManager.clearHistory()
        }
    }

    private suspend fun resolveProblemWithRetry(action: ActionOptions, context: ExecutionContext): ResolveResult {
        var lastError: Exception? = null
        val sid = context.sid
        val activeContext = stateManager.getActiveContext()

        for (attempt in 0..config.maxRetries) {
            try {
                val result = doRunAgentLoop(action, activeContext, attempt)
                return result
            } catch (e: TokenBudgetExceededException) {
                // Budget breach is permanent — do not retry (each retry would
                // throw immediately anyway since the budget is already exceeded).
                logger.error("🛑 resolve.budget.sid={} msg={}", sid, e.message)
                return ResolveResult(activeContext, ActResultHelper.failed(e, action = action.action))
            } catch (e: RequestTokenLimitExceededException) {
                // Per-request limit breach halts the task: report status and
                // wait for the user to raise the limit before re-launching.
                logger.error("🛑 resolve.request-token-limit.sid={} msg={}", sid, e.message)
                return ResolveResult(activeContext, ActResultHelper.failed(e, action = action.action))
            } catch (e: CancellationException) {
                // Cancellation (including the overall resolve timeout) must
                // propagate — retrying a cancelled coroutine defeats structured
                // concurrency and masks the real stop reason.
                throw e
            } catch (e: Exception) {
                lastError = e
                logger.error("💥 resolve.unexpected attempt={} sid={} msg={}", attempt + 1, sid, e.message, e)

                cleanupPartialState(activeContext)

                // Permanent errors (validation/illegal-state) gain nothing from
                // a retry — fail fast instead of burning more LLM tokens.
                if (!retryStrategy.shouldRetry(e)) {
                    logger.error("🛑 resolve.permanent sid={} msg={}", sid, e.message)
                    break
                }
                // Back off before the next attempt; without this the retry loop
                // hammers an already-failing model/browser with zero delay.
                if (attempt < config.maxRetries) {
                    delay(calculateRetryDelay(attempt).milliseconds)
                }
            }
        }

        val actResult = ActResultHelper.failed(lastError ?: Exception("Unknown error"), action.action)

        return ResolveResult(activeContext, actResult)
    }

    private suspend fun doRunAgentLoop(
        initActionOptions: ActionOptions, initContext: ExecutionContext, attempt: Int
    ): ResolveResult {
        initializeResolution(initContext, attempt)

        return doRunCliAgentLoop(initActionOptions, initContext)
    }

    // ─── CLI tool-loop engine (design v0.2) ────────────────────────────────

    /**
     * Native function-calling loop that drives browser4-cli subprocesses via
     * `b4.run`, following the bundled SKILL.md. Completion is signalled by the
     * model calling `system.taskComplete` (no JSON parsing anywhere).
     */
    private suspend fun doRunCliAgentLoop(
        initActionOptions: ActionOptions, initContext: ExecutionContext
    ): ResolveResult {
        var context = initContext
        val startTime = Instant.now()
        cliRunStartedAt = startTime
        val completionRef = AtomicReference<ActionDescription?>()
        val toolExecutions = AtomicInteger(0)
        try {
            val action = initActionOptions.copy(fromRunLoop = true)
            // Initialize the agent memory BEFORE building the tool loop: the
            // memory.* tool specs are registered into CustomToolRegistry on
            // first access, and buildCliToolLoop snapshots the registry for
            // the whole run — a lazy init after this point would leave the
            // memory tools undisclosed to the model.
            val taskId = context.sid
            agentMemory.currentTaskId = taskId
            // Provision the coding workspace mirror HERE, where [codingMode] is
            // definitely known. The mirror is otherwise attempted when the tool
            // manager is constructed, and that lazy init can happen BEFORE the task
            // runner sets codingMode — then a coding run started with an EMPTY
            // working tree and the model wandered the filesystem looking for the
            // checkout (observed: three agents in one backend process, only the
            // first had a mirrored workspace; the coding run listed
            // `D:\workspace\Browser4\…` and `~/.browser4/workspaces` instead of
            // reading its own working tree). The call is idempotent.
            if (codingMode) {
                runCatching { agentToolManager.provisionCodingWorkspaceMirror() }
                    .onFailure { logger.warn("Coding workspace mirror failed: {}", it.message) }
            }
            cliLoopTracer.logEvent("run.start", mapOf("instruction" to action.action))
            // Agent memory: observe the run and inject the recall section
            // (design §5 — static for the whole run, KV prefix preserved).
            val urlCandidate = Sanitizer.extractUrl(action.action)
            agentMemory.sink.taskStarted(
                taskId, uuid.toString(), action.action, "cli",
                urlCandidate = urlCandidate,
            )
            agentMemory.recordTaskDomain(urlCandidate)
            val memorySection = agentMemory.recall.recall(
                action.action, agentMemory.scope, excludeTaskId = taskId,
            )
            // §6.2: archive the previous task's deliverables, reset change
            // tracking (so changeSummary opens at 0), and obtain the inherited
            // workspace-state block to inject into the system prompt. Safe for
            // non-coding runs — it only resets the mutation log.
            val inheritedSection = runCatching {
                agentToolManager.beginTask(taskId, action.action, codingMode)
            }.getOrNull().orEmpty()
            var messages: List<ChatMessage> = listOf(
                SystemMessage.from(cliAgentSystemPrompt() + inheritedSection + memorySection),
                // Task inputs the instruction references but that live outside the
                // isolated workspace are copied into `.b4inputs/` here; the message
                // names their workspace paths (no-op when nothing was copied).
                UserMessage.from(materializeTaskInputs(action.action, context.sid)),
            )
            var textOnlyStreak = 0
            var turn = 0
            val maxTurns = config.maxSteps.coerceAtLeast(1)
            // A new run starts with no verdict and a full repair budget.
            lastVerificationVerdict = null
            repairLoop.reset()
            finishReportRepair.reset()
            logger.info("🚀 cli-agent.start sid={} turns={} instr='{}'",
                context.sid, maxTurns, Strings.compactInline(action.action, 100))

            // Completion rules:
            // 1. Preferred: the model calls system.taskComplete.
            // 2. Fallback: the model worked (≥1 tool executed in this run) and
            //    answers twice in plain text — the second text is the final
            //    report (the finish-gate's zero-tool guard still rejects
            //    fabricated completions).
            // 3. Pure text-only streak with no tools ever executed = stall.
            //
            // P1-2: the inner loop's tool calls/results die with its local
            // message copy, so every round appends a bounded digest of what
            // this round actually executed — the model keeps working FROM
            // THAT POINT in the next round instead of re-executing tools.
            // P2-3: system.exposeTools results are carried across rounds via
            // [exposedSpecsThisRun], so the model never has to re-disclose.
            var exposedSpecsThisRun: List<dev.langchain4j.agent.tool.ToolSpecification>? = null
            // P0 cost guard: the CLI engine used to ignore `agent.token.budget.total`
            // entirely — the budget was enforced only in ContextToAction, so a live
            // browsing run consumed 7.87M input tokens against the 5M default with no
            // abort. Charged from the loop's onModelResponse callback; the breach
            // propagates to resolveProblemWithRetry, which treats it as permanent.
            //
            // The per-task override (`agent run --token-budget`) is resolved here, once
            // per run, and never written into the process-wide registry.
            val tokenBudget = AgentTokenBudget.forRun(session.sessionConfig, tokenBudgetOverride)
            lastRunTokenBudget = tokenBudget.effectiveMaxTotalTokens
            // Coding progress enforcement: rounds without a recorded file
            // mutation. Reset on every round that changes a file (see
            // [codingProgressNudge]).
            var readOnlyCodingRounds = 0
            var mutationsSeen = agentToolManager.codingFs.mutationsSnapshot().size
            // The completion claim a finish-report repair round was already asked
            // for. Identity comparison is enough: taskComplete builds a new
            // ActionDescription per call, so "same object" means the model did not
            // re-report — amend from the trace instead of burning another round.
            var repairedClaim: ActionDescription? = null
            while (!isClosed && turn < maxTurns) {
                val toolsBefore = toolExecutions.get()
                emitRunEvent(AgentRunEvent(AgentRunEvent.ROUND_START, turn = turn + 1))
                // User steering typed while this task runs (interactive session).
                // Appended to the durable message list so it survives the next
                // round's rebuild; the tool loop drains again before every
                // inference for messages that arrive mid-round.
                drainSteering { messages = messages + it }
                // Per-round executed-tool records, populated by the loop's
                // onToolResult callback and summarized into the continuation.
                val roundToolRecords = mutableListOf<RoundToolRecord>()
                // Working memory: re-inject the scratchpad as the tail message
                // every round (replace-tail — all earlier prefixes stay intact
                // for KV reuse, and the compressor never touches the tail).
                val scratchpadText = agentMemory.scratchpad.render()
                var roundMessages =
                    if (scratchpadText != null) messages + UserMessage.from(scratchpadText) else messages
                // Deterministic "stop surveying, start editing" nudge for coding
                // runs that keep re-mapping the same file across rounds.
                if (codingMode) {
                    val nudge = codingProgressNudge(readOnlyCodingRounds)
                    if (nudge != null) {
                        logger.info("✋ coding.nudge after {} read-only round(s)", readOnlyCodingRounds)
                        cliLoopTracer.logEvent("coding.nudge", mapOf("readOnlyRounds" to readOnlyCodingRounds))
                        roundMessages = roundMessages + UserMessage.from(nudge)
                        readOnlyCodingRounds = 0
                    }
                }
                val loop = buildCliToolLoop(
                    action.action, completionRef, toolExecutions,
                    initialSpecsOverride = exposedSpecsThisRun,
                    toolRecords = roundToolRecords,
                    turn = turn + 1,
                    tokenBudget = tokenBudget,
                )
                // P1-1: the inference timeout now applies PER LLM CALL inside
                // AgentToolCallLoop — NOT to the whole round. A round with many
                // tool calls and long subprocess commands (b4.run up to 600s)
                // must not be killed mid-progress by a single global budget.
                val response = loop.generate(roundMessages)
                // Live view: the model's own words are the most informative
                // single line of a round (`agent run --follow` prints them).
                val modelText = response.content
                if (modelText.isNotBlank()) {
                    emitRunEvent(AgentRunEvent(AgentRunEvent.MODEL_TEXT, text = modelText, turn = turn + 1))
                }
                // P2-3: keep whatever the model exposed on the next round.
                exposedSpecsThisRun = loop.exposedSpecsSnapshot()
                // P1.3: remember the newest verification verdict this round
                // produced — the completion decision and the repair budget are
                // driven by it, not by the model's own claim.
                recordVerificationVerdict(roundToolRecords)
                val completion = completionRef.get()
                if (completion != null && completion.isDecidedComplete) {
                    val repair = nextRepairMessage(context)
                    if (repair != null) {
                        // The gate failed and the budget allows another round:
                        // feed the structured diagnostics back and continue the
                        // loop instead of accepting the completion claim.
                        messages = messages + UserMessage.from(repair)
                        turn++
                        continue
                    }
                    // P0.5: the finish report itself may be incomplete (files the
                    // run wrote that the model forgot). Repair, then amend — never
                    // fail a finished run over the bookkeeping list.
                    val reconciled = reconcileFinishReport(
                        completion, context, allowRepair = repairedClaim !== completion,
                    )
                    val finishRepair = reconciled.repairMessage
                    if (finishRepair != null) {
                        repairedClaim = completion
                        messages = messages + UserMessage.from(finishRepair)
                        turn++
                        continue
                    }
                    return completeCliRun(reconciled.completion ?: completion, context)
                }
                val text = response.content
                val executed = toolExecutions.get() > toolsBefore
                // Coding progress: a round that ran tools but recorded no file
                // mutation counts toward the "stop surveying, edit now" nudge.
                // The mutation log (not the per-tool records) is the signal: an
                // overflowed round never surfaces its tool records here.
                if (codingMode && executed) {
                    val mutations = agentToolManager.codingFs.mutationsSnapshot().size
                    if (mutations > mutationsSeen) {
                        mutationsSeen = mutations
                        readOnlyCodingRounds = 0
                    } else {
                        readOnlyCodingRounds++
                    }
                }
                // Internal tool-loop overflow (maxIterations exhausted) is real
                // work, not a stall: keep going with a fresh generate() call.
                val overflow = response.modelError != null
                if (executed || overflow) {
                    textOnlyStreak = 0
                } else {
                    textOnlyStreak++
                    // Real work happened earlier and the model answers in text
                    // again — accept it as the final report.
                    // One text-only response after real work is the final
                    // report (the nudge already told the model to call
                    // taskComplete or continue with tools; a plain-text answer
                    // means it is done).
                    if (toolExecutions.get() > 0 && textOnlyStreak >= 1) {
                        logger.info("✅ cli-agent.text-completion sid={} tools={}",
                            context.sid, toolExecutions.get())
                        val textCompletion = ActionDescription(
                            instruction = action.action,
                            isDecidedComplete = true,
                            summary = text,
                        )
                        // A plain-text report carries no filesChanged at all, so
                        // this is exactly the claim rule 7 rejects when the run
                        // wrote anything. Repair it the same way as taskComplete.
                        val reconciled = reconcileFinishReport(textCompletion, context, allowRepair = true)
                        val finishRepair = reconciled.repairMessage
                        if (finishRepair != null) {
                            messages = messages + UserMessage.from(finishRepair)
                            turn++
                            continue
                        }
                        return completeCliRun(reconciled.completion ?: textCompletion, context)
                    }
                    // `--noop-limit` raises this fuse too: the operator tunes one
                    // "no progress" knob, so the two fuses must stay coupled (the
                    // removed observe-act loop behaved the same way).
                    val stallLimit = if (noopLimitOverride != null) noopLimit else config.textOnlyStallLimit
                    if (stallLimit > 0 && textOnlyStreak >= stallLimit) {
                        logger.info("⛔ cli-agent.text-only-stall sid={} consecutive={} limit={}",
                            context.sid, textOnlyStreak, stallLimit)
                        break
                    }
                }
                val roundDigest = cliRoundToolDigest(roundToolRecords)
                val continuation = if (roundDigest != null) {
                    "$CLI_CONTINUE_NUDGE\n\n$roundDigest"
                } else {
                    CLI_CONTINUE_NUDGE
                }
                messages = when {
                    // Overflow: the inner loop's message list dies with the
                    // generate() call, so without this hand-off the model would
                    // restart the whole round from scratch and re-execute every
                    // already-done tool call (the observed 20-iteration churn).
                    // Feed the executed-tools + newest-results digest back so it
                    // resumes from the cut point instead.
                    overflow -> {
                        cliLoopTracer.logEvent("overflow", mapOf("modelError" to response.modelError!!))
                        messages + UserMessage.from(overflowContinuationMessage(response.modelError!!, progressDigest()))
                    }
                    // Blank model text: keep the nudge-only append (a blank
                    // AiMessage risks provider "content required" rejections;
                    // consecutive UserMessages are accepted by the OpenAI/
                    // DeepSeek-compatible APIs this engine targets, and the
                    // text-only stall fuse still bounds the loop).
                    text.isBlank() -> messages + UserMessage.from(continuation)
                    else -> messages + AiMessage.from(text) + UserMessage.from(continuation)
                }
                turn++
            }

            val completion = completionRef.get()
            if (completion != null && completion.isDecidedComplete) {
                // The loop is over, so no repair round is available: the report is
                // accepted as-is or amended from the run's trace (allowRepair=false).
                val reconciled = reconcileFinishReport(completion, context, allowRepair = false)
                return completeCliRun(reconciled.completion ?: completion, context)
            }
            val stopReason = if (turn >= maxTurns) StopReason.MAX_STEPS else StopReason.NOOP_LIMIT
            logger.info("⛔ cli-agent.no-completion sid={} stop={}", context.sid, stopReason)
            agentMemory.sink.failed(
                context.sid, uuid.toString(),
                "CLI agent stopped without completion: $stopReason", step = turn,
            )
            agentMemory.consolidator?.schedule(context.sid)
            val actResult = buildFinalActResult(initContext.instruction, context, startTime, stopReason)
            return ResolveResult(context, actResult)
        } catch (e: LlmInferenceTimeoutException) {
            // P1-1: a single LLM call exceeded the per-call inference timeout.
            // AgentToolCallLoop distinguishes this from outer cancellation via
            // the dedicated exception type, so this branch only fires for a
            // genuine hung/slow provider call.
            logger.warn("⛔ cli-agent.inference-timeout sid={} after {} ms: {}",
                context.sid, config.llmInferenceTimeoutMs, e.message)
            agentMemory.sink.failed(
                context.sid, uuid.toString(),
                "LLM inference timed out after ${config.llmInferenceTimeoutMs}ms", step = context.step,
            )
            agentMemory.consolidator?.schedule(context.sid)
            return ResolveResult(context, ActResultHelper.failed(e, initContext.instruction))
        } catch (e: CancellationException) {
            // User cancellation or the overall resolve timeout — propagate so
            // the resolve layer can record 'resolveTimeout' and report a
            // timeout instead of a user cancellation.
            logger.info(
                "🛑 cli-agent.cancelled sid={} reason={}",
                context.sid, e.message ?: "user interruption"
            )
            throw e
        } catch (e: TokenBudgetExceededException) {
            // P0 cost guard: the run's token budget is spent. Permanent by definition —
            // rethrow so resolveProblemWithRetry records it without a retry (the retry
            // would throw on its first request anyway, burning nothing but time).
            logger.error("🛑 cli-agent.budget sid={} msg={}", context.sid, e.message)
            agentMemory.sink.failed(
                context.sid, uuid.toString(),
                e.message ?: "token budget exceeded", step = context.step,
            )
            agentMemory.consolidator?.schedule(context.sid)
            throw e
        } catch (e: Exception) {
            // Failure memory event (user cancellation is deliberately excluded).
            agentMemory.sink.failed(context.sid, uuid.toString(), e.message ?: e.javaClass.simpleName)
            agentMemory.consolidator?.schedule(context.sid)
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    /**
     * Copy task inputs that live outside the workspace into `<workspace>/.b4inputs/`
     * and return the instruction that names their workspace paths.
     *
     * A browsing task's workspace is isolated, so a task that names a repository or
     * absolute path can only hit `PATH_DENIED` (observed 2026-10-28: ten minutes of
     * `glob`/`file://`/`dir /s` hunting for a fixture the run was never allowed to
     * read). Materializing the reference keeps the boundary and makes the task doable.
     *
     * Disabled with `-Dbrowser4.agent.copyTaskInputs=false`. Failures are logged and
     * never block the run: the model still sees the original instruction.
     *
     * @param instruction the task text as the user wrote it
     * @param sid session id, for the log/trace event
     * @return [instruction], plus a mapping note when at least one input was copied
     */
    private fun materializeTaskInputs(instruction: String, sid: String): String {
        val enabled = runCatching {
            session.sessionConfig.getBoolean("browser4.agent.copyTaskInputs", true)
        }.getOrDefault(true)
        if (!enabled) return instruction

        return runCatching {
            val searchRoots = buildList {
                runCatching { CodingWorkspace.workspaceRoot }.getOrNull()?.let { add(it) }
                runCatching { Path.of(System.getProperty("user.dir")) }.getOrNull()?.let { add(it) }
            }.distinct()
            val result = TaskInputs.materialize(instruction, agentWorkspaceDir, searchRoots)
            if (result.inputs.isNotEmpty()) {
                logger.info(
                    "📥 cli-agent.inputs sid={} copied={} into={}",
                    sid, result.inputs.size, agentWorkspaceDir.resolve(TaskInputs.INPUT_DIR),
                )
                cliLoopTracer.logEvent(
                    "inputs.copied",
                    mapOf("count" to result.inputs.size, "sid" to sid),
                )
            }
            result.instruction
        }.onFailure {
            logger.warn("Could not materialize task inputs: {}", it.message)
        }.getOrDefault(instruction)
    }

    private fun buildCliToolLoop(
        instruction: String,
        completionRef: AtomicReference<ActionDescription?>,
        toolExecutions: AtomicInteger,
        /**
         * P2-3: tools exposed by the model in earlier rounds of the SAME run.
         * When non-null it replaces the curated core as the initial set, so
         * `system.exposeTools` results survive across outer rounds instead of
         * being forgotten (the old code rebuilt the loop from the core set
         * every round, forcing re-disclosure).
         */
        initialSpecsOverride: List<dev.langchain4j.agent.tool.ToolSpecification>? = null,
        /** P1-2: per-round executed-tool records for the continuation digest. */
        toolRecords: MutableList<RoundToolRecord> = mutableListOf(),
        /** 1-based outer round number this loop serves (event metadata). */
        turn: Int = 0,
        /**
         * P0 cost guard: the run's token budget. Every model request of this loop is
         * charged to it, so a runaway run stops at `agent.token.budget.total` instead
         * of running unbounded (the legacy engine was the only one enforced).
         */
        tokenBudget: AgentTokenBudget? = null,
    ): AgentToolCallLoop {
        val conf = session.sessionConfig
        val initialToolSet = conf.get("browser4.agent.toolLoop.initialToolSet") ?: "core"
        val disclosureEnabled = conf.getBoolean("browser4.agent.toolLoop.toolDisclosureEnabled", true)
        val toolSet = buildCliEngineToolSet(
            allRegistry = agentToolManager.getLangChain4jToolRegistry(),
            systemSpecs = agentToolManager.system.getToolSpecs().values.toList(),
            initialToolSet = initialToolSet,
            disclosureEnabled = disclosureEnabled,
            // Explicit per-task coding flag (command_run's coding argument), not
            // auto-detected: coding tasks get the full coding core for their
            // initial exposure.
            codingMode = codingMode,
            // Per-task policy: a method the preset forbids never enters the registry, so
            // `system.exposeTools` can only ever return `unknown` for it (E4).
            policy = agentToolManager.accessPolicy,
        )
        if (toolSet.usedMode != initialToolSet) {
            logger.warn(
                "cli-agent initialToolSet '{}' matched no CLI tools; falling back to '{}'",
                initialToolSet, toolSet.usedMode
            )
        }
        val initialSpecs = initialSpecsOverride ?: toolSet.initialSpecs
        val taskCompleteName = ToolSpecificationConverter.toolName("system", "taskComplete")
        // One traceability ledger shared by compressor, deduper and loop
        // (compaction-traceability-design.md): references stay resolvable
        // after compression. Every durable rewrite is mirrored to the
        // disk-side audit trail (cli-compactions.jsonl).
        val compactionLedger = CompactionLedger(
            enabled = conf.getBoolean("browser4.agent.toolLoop.compactionLedgerEnabled", true),
            onEntry = { entry -> cliLoopTracer.logLedgerEntry(entry) },
        )
        logger.info(
            "cli-agent tools exposed: {} initial (of {} CLI-domain, disclosure={}, mode='{}', profile={})",
            initialSpecs.size, toolSet.specs.size, toolSet.disclosureSpecs.isNotEmpty(),
            toolSet.usedMode, if (codingMode) "coding" else "browsing",
        )
        return AgentToolCallLoop(
            model = cta.chatModel,
            toolSpecifications = initialSpecs,
            allToolSpecifications = toolSet.disclosureSpecs,
            disclosureListingLimit = conf.getLong("browser4.agent.toolLoop.toolDisclosureListingLimit", 200L)
                .toInt().coerceIn(10, 1_000),
            coordinator = ToolExecutionCoordinator(agentToolManager, toolSet.registry),
            // Browser tasks routinely exceed the default 12-round internal cap
            // (open → snapshot → type → submit → wait → extract); give the CLI
            // engine a higher ceiling, overflow is handled by the outer loop
            // (the model resumes from the executed-tools digest). Coding rounds get
            // a higher floor still: they are cut off mid-chain and restarted from
            // that (lossy) digest.
            maxIterations = cliToolLoopMaxIterations(config.toolLoopMaxIterations, codingMode),
            requestTokenLimiter = cta.requestTokenLimiter,
            // P1-1: per-LLM-call timeout — a long multi-tool round keeps
            // progressing; a hung provider call fails fast instead of
            // killing the whole round mid-work.
            inferenceTimeoutMs = config.llmInferenceTimeoutMs,
            // P3-7: bound the main request's output deterministically instead
            // of relying on provider defaults.
            maxOutputTokens = conf.getLong("browser4.agent.toolLoop.maxOutputTokens", 8_192L)
                .toInt().coerceIn(256, 65_536),
            // One shared traceability ledger across compressor, deduper and
            // loop (compaction-traceability-design.md).
            compressor = cliToolLoopCompressor(toolSet.specs, compactionLedger),
            // Web-context optimization: repeated page views fold into
            // references/diffs instead of resending full snapshots.
            pageViewDeduper = cliPageViewDeduper(compactionLedger),
            maxOverflowRetries = conf.getLong("browser4.agent.toolLoop.maxOverflowRetries", 1L)
                .toInt().coerceIn(0, 5),
            compactionLedger = compactionLedger,
            // Complete prompt + run tracing: dump the exact request and every
            // tool execution (see CliLoopTracer). The specs are the ones the
            // model request actually carries (exposed set + meta tools), NOT
            // the full registry — the dump must reconstruct the real prompt.
            onBeforeGenerate = { msgs, specs -> cliLoopTracer.logPrompt(msgs, specs) },
            // The provider's usage for this request reaches the run's stats as a
            // `run.usage` step (token accounting for `/stats`): the tracer writes
            // the same numbers to `cli-usage.jsonl`, but only on disk — a client
            // following the task had no way to see token consumption at all.
            onModelResponse = { seq, response ->
                cliLoopTracer.logResponse(seq, response)
                // Charged BEFORE the usage event so the reported cumulative total
                // includes this request (a task-level budget that is about to abort
                // must still be visible), then reported with the effective budget.
                tokenBudget?.let { budget ->
                    runCatching { response.tokenUsage() }.getOrNull()?.let { usage ->
                        // A provider that omits usage contributes nothing; the request
                        // is still counted by the loop's own totals.
                        budget.charge(
                            usage.inputTokenCount()?.toLong() ?: 0L,
                            usage.outputTokenCount()?.toLong() ?: 0L,
                        )
                    }
                }
                emitUsageEvent(response, turn, budget = tokenBudget)
            },
            onToolResult = { req, result, durationMs ->
                // Live view: one event per executed tool (`agent run --follow`).
                val resultText = result.text()
                emitRunEvent(
                    AgentRunEvent(
                        AgentRunEvent.TOOL_RESULT,
                        tool = req.name(),
                        ok = !isFailedToolResult(resultText),
                        durationMs = durationMs,
                        text = resultText,
                        turn = turn,
                    )
                )
                cliLoopTracer.logTool(req, result, durationMs)
                // P1-2: record executed tools (meta plumbing excluded — it is
                // neither task progress nor useful context for the model).
                if (req.name() !in ToolDisclosureTools.META_NAMES) {
                    toolRecords += RoundToolRecord(req.name(), result.text())
                }
                // Agent memory: every executed tool becomes a ToolExecuted
                // event (sanitized at the sink boundary).
                val memory = agentMemory
                if (MemoryConfig.enabled) {
                    memory.sink.toolExecuted(
                        memory.currentTaskId ?: "unknown", uuid.toString(), req.name(),
                        req.arguments() ?: "{}",
                        ok = !isFailedToolResult(result.text()),
                        result.text(), durationMs, req.id() ?: "",
                    )
                }
            },
            // Page-view timeline: log every view tool execution with its
            // final (decorated) form so URL/fingerprint survive compaction
            // as a disk-side, machine-readable link index — and mirror the
            // same view into the agent memory (PageViewed events).
            onToolDecorated = { req, raw, decorated ->
                cliLoopTracer.logPageView(req, raw, decorated)
                val memory = agentMemory
                if (MemoryConfig.enabled) {
                    val text = decorated.text()
                    val viewType = when {
                        text == raw.text() -> "full"
                        text.contains(PageViewDeduper.DUPLICATE_MARKER) -> "reference"
                        text.contains(PageViewDeduper.DIFF_MARKER) -> "diff"
                        else -> "full"
                    }
                    val url = Sanitizer.extractUrl(text) ?: Sanitizer.extractUrl(req.arguments())
                    memory.sink.pageViewed(
                        memory.currentTaskId ?: "unknown", uuid.toString(),
                        url ?: "", "", viewType, sha256Brief(text),
                    )
                }
            },
            onToolExecuted = {
                val executed = toolExecutions.incrementAndGet()
                // The CLI engine can execute dozens of tools inside a single
                // step; without periodic events the status stays "Agent starting
                // up" until the whole step resolves. Throttled liveness events
                // let status/SSE observers see that the agent is still working.
                if (executed % CLI_TOOL_PROGRESS_EVENT_INTERVAL == 0) {
                    AgentEventBus.emitAgentEvent(
                        eventType = AgenticEvents.PerceptiveAgent.ON_TOOL_EXECUTED,
                        agentId = uuid.toString(),
                        message = "Executed $executed tool call(s)",
                        metadata = mapOf("toolExecutions" to executed)
                    )
                }
            },
            onToolRequest = { name, argsJson ->
                emitRunEvent(
                    AgentRunEvent(AgentRunEvent.TOOL_CALL, tool = name, arguments = argsJson, turn = turn)
                )
                if (name == taskCompleteName) {
                    runCatching {
                        val completion = TaskCompletion.fromJson(argsJson)
                        if (completion.summary.isBlank()) {
                            logger.warn("cli-agent taskComplete rejected: blank summary")
                        } else {
                            completionRef.set(
                                ActionDescription(
                                    instruction = instruction,
                                    isDecidedComplete = true,
                                    summary = completion.summary,
                                    keyFindings = completion.keyFindings,
                                    filesChanged = completion.filesChanged,
                                    problems = completion.problems,
                                )
                            )
                        }
                    }.onFailure { logger.warn("cli-agent taskComplete parse failed: {}", it.message) }
                }
            },
            // Steering arrives mid-round for the interactive session: inject it
            // right before the next inference so the model can react within the
            // current round instead of after it.
            onBeforeIteration = { innerMessages -> drainSteering { innerMessages.add(it) } },
        )
    }

    /**
     * Report one run step to the task runner.
     *
     * Observer failures are swallowed on purpose: rendering progress must never
     * be able to break the run it observes (`StatefulAgentRunner` listens for
     * every tool call of every task in the session).
     */
    private fun emitRunEvent(event: AgentRunEvent) {
        runEventListener?.let { listener ->
            runCatching { listener(event) }
                .onFailure { logger.debug("run event listener failed: {}", it.message) }
        }
    }

    /**
     * Report one model request's provider usage as a [AgentRunEvent.USAGE] step.
     *
     * One event per successful request (not one per run): a tool-calling round
     * may issue several requests, and compaction issues its own, so a single
     * end-of-run total could only be approximated. The token fields stay `null`
     * when the provider reports no usage — the request is still counted.
     *
     * @param response the provider's answer for this request
     * @param turn 1-based outer round the request belongs to (0 for auxiliary calls)
     * @param category [AgentRunEvent.USAGE_CATEGORY_COMPACTION] for a compaction call
     * @param budget the run's budget, when it has one: its effective limit and the
     *   cumulative consumption are what let a supervisor see 5M tokens approaching
     *   instead of only discovering it in the abort (2026-10-28 baseline: 5,006,041
     *   tokens consumed, zero advance warning in `--follow`)
     */
    private fun emitUsageEvent(
        response: ChatResponse,
        turn: Int,
        category: String? = null,
        budget: AgentTokenBudget? = null,
    ) {
        val usage = runCatching { response.tokenUsage() }.getOrNull()
        emitRunEvent(
            AgentRunEvent(
                AgentRunEvent.USAGE,
                text = category,
                turn = turn,
                inputTokens = usage?.inputTokenCount(),
                outputTokens = usage?.outputTokenCount(),
                totalTokens = usage?.totalTokenCount(),
                budgetTokens = budget?.effectiveMaxTotalTokens,
                cumulativeTokens = budget?.consumedTotal,
            )
        )
    }

    /**
     * Drain queued user steering messages into [sink].
     *
     * Steering is drained at every model round-trip — at the top of the outer
     * round, and inside the tool loop before every inference ([AgentToolCallLoop.onBeforeIteration]) —
     * so a message typed while the agent is mid-round reaches the very next
     * model request instead of waiting for the round (up to 60 tool calls) to
     * finish.
     *
     * @param sink receives one [UserMessage] carrying all pending lines
     * @return the number of injected lines (0 when nothing was queued)
     */
    private fun drainSteering(sink: (ChatMessage) -> Unit): Int {
        val lines = runCatching { steeringSource?.invoke() }
            .getOrNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (lines.isEmpty()) return 0

        val text = lines.joinToString("\n")
        runCatching { sink(UserMessage.from("$CLI_STEERING_PREFIX\n$text")) }
            .onFailure { logger.warn("cli-agent.steer injection failed: {}", it.message) }
        emitRunEvent(AgentRunEvent(AgentRunEvent.STEER, text = text))
        logger.info(
            "🧭 cli-agent.steer agent={} lines={} text='{}'",
            uuid, lines.size, Strings.compactInline(text, 100)
        )
        return lines.size
    }

    /**
     * Continuation prompt handed back to the model after an internal tool-loop
     * overflow: the [modelError] carries a bounded digest of the executed tools
     * and their newest results, so the model resumes from the cut point instead
     * of re-executing the whole round (the overflowed loop's own message list
     * is discarded by [AgentToolCallLoop.generate]).
     */
    private fun overflowContinuationMessage(modelError: String, progress: String): String = buildString {
        append(
            "Your previous tool round was cut short after exceeding the per-round " +
                "iteration cap. The tools that already executed and the newest results " +
                "are summarized below. Continue the task FROM THIS POINT — do not " +
                "re-execute tools that already succeeded unless you need their full output.\n\n"
        )
        append(modelError)
        append("\n\n## Progress so far (do not start over)\n")
        append(progress)
        append(
            "\nIf a data file is incomplete, append the missing rows instead of rewriting it; " +
                "re-read `.b4scratch/progress.txt` (when it exists) to see which items are done."
        )
    }

    /**
     * What this run has already written, for the overflow continuation.
     *
     * An overflow restarts the round from a lossy digest (the newest few tool results,
     * ~2K chars). Without the files the run already produced, the model re-fetches and
     * rewrites finished work — the 2026-10-27 run crawled the same 20 pages four times
     * across six overflows. `coding.changeSummary` is the authoritative list of files
     * this run wrote; it is bounded here because the block ships in every continuation.
     */
    private fun progressDigest(maxLines: Int = 20, maxChars: Int = 1_200): String =
        progressDigestFromSummary(
            runCatching { agentToolManager.codingFs.changeSummary() }.getOrNull(),
            maxLines,
            maxChars,
        )

    /**
     * Complete-prompt + run tracing for the CLI engine.
     *
     * Under the agent's aux log dir (`<aux>/agent/<startTime>/<uuid>/`):
     * - `cli-prompt/<ts>.<seq>.request.json` — one file per model round-trip:
     *   the EXACT `messages` list (system prompt, history, tool-call messages
     *   and full tool results) plus the tool specifications the model saw
     *   (the exposed set + disclosure meta tools — NOT the full registry),
     *   with an `estimatedTokens` fallback (chars/4 of the compact payload);
     * - `cli-usage.jsonl` — one line per model request: requestSeq (pairs with
     *   the request dump), real provider input/output/total tokens, finish
     *   reason;
     * - `cli-tool-trace.jsonl` — one line per executed tool: name, arguments,
     *   full result text, duration;
     * - `page-timeline.jsonl` — one line per page view: tool, callId,
     *   viewType (full/reference/diff), url, title, fingerprint — a
     *   machine-readable link index that survives context compaction;
     * - `cli-compactions.jsonl` — one line per compaction-ledger entry
     *   (registered/folded/pruned/compacted with token accounts);
     * - `cli-events.jsonl` — run-level events: run.start / overflow / complete.
     */
    private class CliLoopTracer(
        agentUuid: UUID,
        agentStartTime: Instant,
        private val enabled: Boolean,
        private val viewToolNames: Set<String>,
    ) {
        private val logger = getLogger(CliLoopTracer::class)

        private val runDir: Path by lazy {
            AgentPaths.resolveTraceRunDir(agentStartTime, agentUuid)
        }

        private val jsonlWriter by lazy { MultiSinkMessageWriter(runDir) }
        private val promptDir: Path by lazy {
            runDir.resolve("cli-prompt").also { Files.createDirectories(it) }
        }
        private val promptSeq = AtomicInteger(0)
        private val toolSeq = AtomicInteger(0)
        private val pageSeq = AtomicInteger(0)

        /** Dump the exact prompt (messages + tool specs) sent to the model. */
        fun logPrompt(
            messages: List<ChatMessage>,
            toolSpecifications: List<dev.langchain4j.agent.tool.ToolSpecification>,
        ) {
            if (!enabled) return
            try {
                val requestSeq = promptSeq.incrementAndGet()
                val basePayload = mapOf(
                    "requestSeq" to requestSeq,
                    "timestamp" to AppPaths.fromNow(),
                    "toolSpecifications" to toolSpecifications.map { spec ->
                        mapOf(
                            "name" to spec.name(),
                            "description" to (spec.description() ?: ""),
                            "parameters" to runCatching {
                                pulsarObjectMapper().writeValueAsString(spec.parameters())
                            }.getOrElse { "{}" },
                        )
                    },
                    "messages" to messages.map { serializeMessage(it) },
                    "messageCount" to messages.size,
                )
                // Fallback estimate persisted with the dump so token totals
                // stay answerable even when the provider reports no usage
                // (compact JSON chars / 4, English-heavy heuristic).
                val estimatedChars = pulsarObjectMapper().writeValueAsString(basePayload).length
                val payload = basePayload + ("estimatedTokens" to (estimatedChars / 4).coerceAtLeast(1))
                val path = promptDir.resolve("${AppPaths.fromNow()}.$requestSeq.request.json")
                val json = pulsarObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(payload)
                // A lone surrogate (from truncated model output, a digest cut, an
                // already-pruned result, ...) is not encodable as UTF-8, and
                // Files.writeString uses a REPORT encoder: one bad character used
                // to lose this dump for every later round of the run (the poisoned
                // message stays in the conversation). Repair it instead.
                Files.writeString(path, SafeText.sanitize(json))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI prompt log: {}", e.message)
            }
        }

        /**
         * Persist the real provider token usage for one model request, paired
         * with its request dump via `requestSeq`. Append-only JSONL so every
         * round's usage survives (the loop sums usage across rounds).
         */
        fun logResponse(requestSeq: Int, response: ChatResponse) {
            if (!enabled) return
            try {
                val usage = response.tokenUsage()
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "requestSeq" to requestSeq,
                    "inputTokens" to usage?.inputTokenCount(),
                    "outputTokens" to usage?.outputTokenCount(),
                    "totalTokens" to usage?.totalTokenCount(),
                    "finishReason" to response.finishReason()?.name,
                )
                jsonlWriter.writeTo(payload, runDir.resolve("cli-usage.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI usage trace: {}", e.message)
            }
        }

        /**
         * Persist the usage of an auxiliary (compaction-summarization) model
         * call to `cli-usage.jsonl` — paired with the main-loop lines by
         * category so token accounting covers compaction too (P2-5).
         */
        fun logCompactionUsage(response: ChatResponse) {
            if (!enabled) return
            try {
                val usage = response.tokenUsage()
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "requestSeq" to 0,
                    "category" to "compaction",
                    "inputTokens" to usage?.inputTokenCount(),
                    "outputTokens" to usage?.outputTokenCount(),
                    "totalTokens" to usage?.totalTokenCount(),
                    "finishReason" to response.finishReason()?.name,
                )
                jsonlWriter.writeTo(payload, runDir.resolve("cli-usage.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI compaction usage trace: {}", e.message)
            }
        }

        /** Append one executed tool (arguments + full result text + duration). */
        fun logTool(request: ToolExecutionRequest, result: ToolExecutionResultMessage, durationMs: Long) {
            if (!enabled) return
            try {
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "seq" to toolSeq.incrementAndGet(),
                    "tool" to request.name(),
                    "arguments" to (request.arguments() ?: "{}"),
                    "durationMs" to durationMs,
                    "resultText" to result.text(),
                )
                jsonlWriter.writeTo(payload, runDir.resolve("cli-tool-trace.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI tool trace: {}", e.message)
            }
        }

        /**
         * Append one page view to the disk-side page timeline. Only view tools
         * ([viewToolNames]) are recorded; [raw] is the executed result and
         * [decorated] the message actually appended (identical → `full`,
         * duplicate fold → `reference`, diff → `diff`). URL/title are
         * best-effort extractions (from the CLI arguments, then from the
         * result text) so the timeline stays a machine-readable link index
         * even after the conversation is compacted.
         */
        fun logPageView(
            request: ToolExecutionRequest,
            raw: ToolExecutionResultMessage,
            decorated: ToolExecutionResultMessage,
        ) {
            if (!enabled) return
            val tool = request.name()
            if (!PageViewDeduper.matchesViewTool(tool, viewToolNames)) return
            try {
                val rawText = raw.text()
                if (rawText.isBlank()) return
                val decoratedText = decorated.text()
                val viewType = when {
                    decoratedText == rawText -> "full"
                    decoratedText.contains(PageViewDeduper.DUPLICATE_MARKER) -> "reference"
                    decoratedText.contains(PageViewDeduper.DIFF_MARKER) -> "diff"
                    else -> "full"
                }
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "seq" to pageSeq.incrementAndGet(),
                    "tool" to tool,
                    "callId" to raw.id(),
                    "viewType" to viewType,
                    "url" to extractPageUrl(request.arguments(), rawText),
                    "title" to extractPageTitle(rawText),
                    "fingerprint" to PageViewDeduper.fingerprintOf(rawText),
                    "textChars" to rawText.length,
                    "arguments" to (request.arguments() ?: "{}"),
                )
                jsonlWriter.writeTo(payload, runDir.resolve("page-timeline.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI page timeline trace: {}", e.message)
            }
        }

        /**
         * Mirror one compaction-ledger entry to `cli-compactions.jsonl` — the
         * durable audit trail of every conversation rewrite (registered /
         * folded / pruned / compacted with token accounts). Survives the
         * in-memory ledger being discarded with the loop.
         */
        fun logLedgerEntry(entry: CompactionLedger.Entry) {
            if (!enabled) return
            try {
                val payload = when (entry) {
                    is CompactionLedger.Entry.ResultRegistered -> mapOf(
                        "type" to "registered",
                        "callId" to entry.callId,
                        "messageIndex" to entry.messageIndex,
                    )
                    is CompactionLedger.Entry.Folded -> mapOf(
                        "type" to "folded",
                        "callId" to entry.callId,
                        "originalIndex" to entry.originalIndex,
                        "compactIndex" to entry.compactIndex,
                    )
                    is CompactionLedger.Entry.Pruned -> mapOf(
                        "type" to "pruned",
                        "callId" to entry.callId,
                        "shadowedIndex" to entry.shadowedIndex,
                        "replacementIndex" to entry.replacementIndex,
                        "shadowedTokens" to entry.shadowedTokens,
                        "removedTokens" to entry.removedTokens,
                    )
                    is CompactionLedger.Entry.Compacted -> mapOf(
                        "type" to "compacted",
                        "compactionId" to entry.compactionId,
                        "reason" to entry.reason,
                        "shadowedRange" to "${entry.shadowedRange.first}-${entry.shadowedRange.last}",
                        "replacementIndex" to entry.replacementIndex,
                        "shadowedTokens" to entry.shadowedTokens,
                        "replacementTokens" to entry.replacementTokens,
                        "failure" to entry.failure,
                    )
                }
                jsonlWriter.writeTo(
                    mapOf("timestamp" to AppPaths.fromNow()) + payload,
                    runDir.resolve("cli-compactions.jsonl"),
                )
            } catch (e: Exception) {
                logger.warn("Failed to write CLI compaction ledger trace: {}", e.message)
            }
        }

        /** Best-effort page URL: from the CLI arguments, then from the result text. */
        private fun extractPageUrl(argumentsJson: String?, resultText: String): String? {
            argumentsJson?.let { args ->
                URL_IN_TEXT.find(args)?.value?.trimEnd('"', '\'', ')', ']')?.let { return it }
            }
            val fromText = resultText.lineSequence()
                .mapNotNull { line ->
                    URL_LINE_PATTERN.find(line)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                .firstOrNull()
            return fromText?.trimEnd('"', '\'', ')', ']')
        }

        /** Best-effort page title from the result text (first `title:`-ish line). */
        private fun extractPageTitle(resultText: String): String? =
            resultText.lineSequence()
                .mapNotNull { line -> TITLE_LINE_PATTERN.find(line)?.groupValues?.get(1)?.trim() }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.take(200)

        companion object {
            /** Any http(s) URL inside the b4.run command string. */
            private val URL_IN_TEXT = Regex("""https?://[^\s"')\]]+""")
            /** `url: <value>` / `page url: <value>` / `URL = <value>` style lines. */
            private val URL_LINE_PATTERN = Regex(
                """(?im)^\s*(?:page\s+)?(?:url|URL)\s*[:=]\s*(\S.*)$"""
            )
            /** `title: <value>` / `page title: <value>` style lines. */
            private val TITLE_LINE_PATTERN = Regex(
                """(?im)^\s*(?:page\s+)?title\s*[:=]\s*(.+)$"""
            )
        }

        /** Append a run-level event (run.start / overflow / complete). */
        fun logEvent(event: String, detail: Map<String, Any?>) {
            if (!enabled) return
            try {
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "event" to event,
                ) + detail
                jsonlWriter.writeTo(payload, runDir.resolve("cli-events.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI event trace: {}", e.message)
            }
        }

        private fun serializeMessage(message: ChatMessage): Map<String, Any?> = when (message) {
            is SystemMessage -> mapOf("type" to "system", "text" to message.text())
            is UserMessage -> mapOf("type" to "user", "text" to (message.singleText() ?: message.toString()))
            is AiMessage -> mapOf(
                "type" to "ai",
                "text" to (message.text() ?: ""),
                "toolExecutionRequests" to message.toolExecutionRequests().orEmpty().map { request ->
                    mapOf(
                        "name" to request.name(),
                        "arguments" to (request.arguments() ?: "{}"),
                    )
                },
            )
            is ToolExecutionResultMessage -> mapOf(
                "type" to "tool_result",
                "toolName" to message.toolName(),
                "text" to message.text(),
            )
            else -> mapOf("type" to "unknown", "text" to message.toString())
        }
    }

    /** Auto context compression for the CLI tool loop (long multi-step tasks). */
    private fun cliToolLoopCompressor(
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        ledger: CompactionLedger,
    ): ToolLoopCompressor? {
        val conf = session.sessionConfig
        if (!conf.getBoolean("browser4.agent.toolLoop.compressionEnabled", true)) return null
        return ToolLoopCompressor(
            enabled = true,
            thresholdTokens = conf.getLong("browser4.agent.toolLoop.compressionThresholdTokens", 60_000L)
                .coerceAtLeast(1_000L),
            retainTokens = conf.getLong("browser4.agent.toolLoop.retainTokens", 24_000L)
                .coerceAtLeast(1_000L),
            pruneThresholdChars = conf.getLong("browser4.agent.toolLoop.pruneThresholdChars", 1_500L)
                .toInt().coerceAtLeast(100),
            pruneHeadChars = conf.getLong("browser4.agent.toolLoop.pruneHeadChars", 800L)
                .toInt().coerceAtLeast(0),
            pruneTailChars = conf.getLong("browser4.agent.toolLoop.pruneTailChars", 400L)
                .toInt().coerceAtLeast(0),
            retainLatestPageView = conf.getBoolean("browser4.agent.toolLoop.retainLatestPageView", true),
            viewToolNames = cliViewToolNames(conf),
            maxResultTokens = conf.getLong("browser4.agent.toolLoop.maxToolResultTokens", 20_000L)
                .coerceAtLeast(0L),
            // Knowledge documents (system.skillDoc results) must reach the
            // model whole — never shred them into head+tail shards.
            protectedToolNames = (conf.get("browser4.agent.toolLoop.protectedToolNames")
                ?: "system_skillDoc")
                .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
            ledger = ledger,
            requireShrink = conf.getBoolean("browser4.agent.toolLoop.requireShrink", true),
            summarizationRetries = conf.getLong("browser4.agent.toolLoop.summarizationRetries", 1L)
                .toInt().coerceIn(0, 5),
            audit = conf.getBoolean("browser4.agent.toolLoop.auditCompaction", true),
        ) { prefix -> summarizeCliLoop(prefix, specs) }
    }

    /**
     * Web-context deduper for the CLI agent: folds repeated page content at
     * append time. See docs-dev/copilot/web-page-context-optimization-design.md.
     */
    private fun cliPageViewDeduper(ledger: CompactionLedger): PageViewDeduper? {
        val conf = session.sessionConfig
        if (!conf.getBoolean("browser4.agent.toolLoop.pageViewDedupEnabled", true)) return null
        return PageViewDeduper(
            enabled = true,
            diffEnabled = conf.getBoolean("browser4.agent.toolLoop.pageViewDiffEnabled", true),
            diffMaxChars = conf.getLong("browser4.agent.toolLoop.pageViewDiffMaxChars", 3_000L)
                .toInt().coerceAtLeast(500),
            digestChars = conf.getLong("browser4.agent.toolLoop.pageViewDigestChars", 300L)
                .toInt().coerceAtLeast(100),
            duplicateFoldEnabled = conf.getBoolean("browser4.agent.toolLoop.duplicateFoldEnabled", true),
            viewToolNames = cliViewToolNames(conf),
            ledger = ledger,
        )
    }

    private fun cliViewToolNames(conf: ImmutableConfig): Set<String> =
        (conf.get("browser4.agent.toolLoop.viewToolNames")
            ?: "ariaSnapshot,textContent,snapshot,dump,htmlsnapshot,extract")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private suspend fun summarizeCliLoop(
        prefix: List<ChatMessage>,
        toolSpecifications: List<dev.langchain4j.agent.tool.ToolSpecification>,
    ): String {
        // P2-5: the compaction summary cap is configurable like the
        // ContextToAction path, not hardcoded.
        val maxTokens = session.sessionConfig
            .getLong("browser4.agent.toolLoop.summarizationMaxTokens", 2_048L)
            .toInt().coerceIn(128, 16_384)
        val request = ChatRequest.builder()
            .messages(prefix + UserMessage.from(ToolLoopCompressor.COMPACTION_INSTRUCTION))
            .toolSpecifications(toolSpecifications)
            .maxOutputTokens(maxTokens)
            .build()
        val response = cta.chatModel.langChainChat(request, "cta-compaction")
        // P2-5: record the compaction call's usage so token accounting is not
        // blind to the auxiliary summarization requests.
        cliLoopTracer.logCompactionUsage(response)
        // The same call also reaches the run stats: compaction tokens are part of
        // what the run consumed, and hiding them made `/stats` under-report.
        emitUsageEvent(response, turn = 0, category = AgentRunEvent.USAGE_CATEGORY_COMPACTION)
        return response.aiMessage().text() ?: ""
    }

    /**
     * Finish a CLI-engine run: record the (already reconciled) completion and
     * push the completed state into the agent history so callers
     * (StatefulAgentRunner) can surface the final summary (the loop itself
     * creates no step states).
     *
     * The finish-report gate runs in [reconcileFinishReport] before this is
     * called, so [completion]'s `filesChanged` is already complete — possibly
     * amended from the run's mutation trace.
     */
    private suspend fun completeCliRun(
        completion: ActionDescription,
        context: ExecutionContext,
    ): ResolveResult {
        cliLoopTracer.logEvent(
            "complete",
            mapOf(
                "summary" to (completion.summary ?: ""),
                "keyFindings" to (completion.keyFindings ?: emptyList<String>()),
                "filesChanged" to (completion.filesChanged ?: emptyList<String>()),
            )
        )
        acceptCompletion(completion, context)
        stateManager.addToHistory(context.agentState)
        // Agent memory: record the successful completion (idempotent per task)
        // and schedule the L0→L1 knowledge deposit (PEM fusion, M3).
        agentMemory.sink.completed(
            context.sid, uuid.toString(), completion.summary ?: "",
            completion.keyFindings, completion.filesChanged, completion.problems,
            durationMs = cliRunStartedAt?.let { Duration.between(it, Instant.now()).toMillis() } ?: 0,
        )
        // User preference memory: explicit statements in the summary only
        // (e.g. "以后用中文输出" — no implicit inference).
        agentMemory.applyUserPreferences(completion.summary)
        agentMemory.consolidator?.schedule(context.sid)
        return ResolveResult(context, ActResultHelper.complete(completion))
    }

    /** Short SHA-256 hex (16 chars) of a text — page-view fingerprint. */
    private fun sha256Brief(text: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault(Integer.toHexString(text.hashCode()))

    /** System prompt for the CLI tool-loop engine: role + resident quick reference + hygiene rules. */
    private fun cliAgentSystemPrompt(): String = buildString {
        append(CliAgentPrompt.role(codingMode, config.repairMaxRounds, agentWorkspaceDir))
        // The resident browsing quick reference is what a browsing run needs every
        // round; a coding run gets its role facts from CliAgentPrompt instead, so it
        // does not start by reading browser CLI docs (observed waste).
        if (!codingMode) {
            append("\n\n### CLI Quick Reference (resident)\n\n")
            // Resident distilled skill: guaranteed present in every request (even
            // after compression), while the full SKILL.md stays on-demand.
            append(
                agentToolManager.system.skillDocStrict("quickstart.md")
                    ?: agentToolManager.system.skillDocMetadata("SKILL.md")
            )
            append(
                """

                Need more detail? Call system.skillDoc("SKILL.md") with NO offset: it returns the
                reference map — the section headings plus every bundled reference doc with the
                offset to read it from. Then load only what this task needs:
                system.skillDoc("bulk-collection.md"), system.skillDoc("agent-workspace.md"),
                system.skillDoc("htmlsnapshot.md"), ... or a slice with
                system.skillDoc("SKILL.md", offset=<n>). Each result header carries
                offset/nextOffset/totalLength; never load the whole document up front.
                """.trimIndent()
            )
        }
        append("\n\n")
        // The per-task policy, not the server default: `agent run --access read` must tell the
        // model about its boundaries in the first round, and a `--access full` task running on a
        // backend configured for `read` must not be told it is restricted.
        append(CliAgentPrompt.hygiene(codingMode, agentWorkspaceDir, agentToolManager.accessPolicy))
    }

    private suspend fun initializeResolution(initContext: ExecutionContext, attempt: Int) {
        val sid = initContext.sid
        logger.info(
            "🚀 agent.start sid={} step={} url={} instr='{}' attempt={} maxSteps={} maxRetries={}",
            sid,
            initContext.step,
            initContext.targetUrl,
            Strings.compactInline(initContext.instruction, COMPACT_INLINE_INSTRUCTION_LENGTH),
            attempt + 1,
            config.maxSteps,
            config.maxRetries
        )
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    private fun handleObserveException(e: Exception, context: ExecutionContext) {
        val failures = try {
            circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        } catch (cbError: CircuitBreakerTrippedException) {
            throw PerceptiveAgentError.PermanentError(cbError.message ?: "Circuit breaker tripped", cbError)
        }

        logger.error("🤖❌ action.gen.fail sid={} failures={} msg={}", context.sid, failures, e.message, e)
    }

    private fun handleResolutionFailure(
        e: Exception, context: ExecutionContext, startTime: Instant
    ): PerceptiveAgentError {
        val executionTime = Duration.between(startTime, Instant.now())
        logger.error(
            "💥 agent.fail sid={} steps={} dur={} err={}", context.sid, context.step, executionTime, e.message, e
        )
        runCatching { stateManager.removeLastIfStep(context.step) }.onFailure {
            logger.warn(
                "⚠️ rollback failed sid={} step={} msg={}",
                context.sid,
                context.step,
                e.message
            )
        }
        return classifyError(e, context.step)
    }

    private fun classifyError(e: Exception, step: Int) = retryStrategy.classifyError(e, "step $step")

    private fun calculateRetryDelay(attempt: Int) = retryStrategy.calculateDelay(attempt)

    private fun cleanupPartialState(context: ExecutionContext) {
        try {
            logger.info("🧹 cleanup.partial sid={} step={}", context.sid, context.step)
            circuitBreaker.reset()
        } catch (e: Exception) {
            logger.warn("⚠️ cleanup.partial.fail sid={} msg={}", context.sid, e.message)
        }
    }

    // ─── Finish report gate ──────────────────────────────────────────────────

    /**
     * Gather the completion evidence and apply the deterministic rules.
     *
     * Side-effect free (the only IO is `git status` on the finish workspace), so
     * the CLI engine can evaluate a claim, hand the diagnostics back for a repair
     * round, and evaluate the next claim again.
     */
    private fun evaluateFinishReport(action: ActionDescription, context: ExecutionContext): FinishReportGate.Verdict {
        // Two sources: outer states (one per model turn with an executed tool)
        // and inner-loop executions recorded by the tool-calling coordinator.
        val runStates = stateHistory.snapshotFor(context.sessionId).states
        val outerExecuted = runStates.filter { it.toolCallResult != null }
        val executedNames = outerExecuted
            .mapNotNull { s -> s.actionDomain?.let { d -> "$d.${s.method}" } }
            .toSet() + innerToolExecutionNames
        val filesChanged = action.filesChanged.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val workspace = finishWorkspaceRoot()
        val gitChanged = workspace?.let { gitChangedFiles(it) }
        if (workspace != null && gitChanged == null) {
            logger.debug("finish.report: git unavailable in {} — filesChanged cross-check skipped", workspace)
        }

        return FinishReportGate.evaluate(
            FinishEvidence(
                executedToolCalls = outerExecuted.size + innerToolExecutionCount,
                executedToolNames = executedNames,
                gates = action.gates.orEmpty(),
                filesChanged = filesChanged,
                gitChangedFiles = gitChanged,
                requiredGates = config.finishRequiredGates,
                claimedDeletedFiles = action.deletedFiles.orEmpty(),
                recordedMutations = agentToolManager.codingFs.mutationsSnapshot()
                    .map { "${it.op} ${it.path}" },
                claimedVerdictJson = extractVerdictJson(action.summary),
                actualVerdictJson = (lastVerificationVerdict?.toJson() ?: lastInnerVerdictJson),
                gitDeletedFiles = workspace?.let { gitDeletedFiles(it) },
            )
        )
    }

    /** Outcome of reconciling one completion claim with the finish-report gate. */
    private data class FinishReconciliation(
        /** The claim to accept (its `filesChanged` is complete after an amendment). */
        val completion: ActionDescription? = null,
        /** A user message to inject: the loop must run another round. */
        val repairMessage: String? = null,
    )

    /**
     * Reconcile a completion claim with the run's evidence before accepting it.
     *
     * Rule 7 of [FinishReportGate] compares the report with the run's own mutation
     * trace. A long run writes dozens of helper files (one `.js` per
     * `b4 run eval --file`) and the model rebuilds `filesChanged` from a compressed
     * memory of the run, so the list is often short — that is a bookkeeping
     * omission, not a false completion, and it used to fail a finished task with
     * `Invalid state: step 0 - Finish report rejected: …`.
     *
     * Policy (see [FinishReportRepair]): one bounded repair round per attempt,
     * then amend the report from the trace. Every other violation still rejects.
     *
     * @param allowRepair false when the run's loop cannot take another round.
     */
    private fun reconcileFinishReport(
        action: ActionDescription,
        context: ExecutionContext,
        allowRepair: Boolean,
    ): FinishReconciliation {
        val sid = context.sessionId.take(8)
        val verdict = evaluateFinishReport(action, context)
        verdict.warnings.forEach { warning ->
            logger.warn("⚠ finish.report sid={} {}", sid, warning)
        }
        if (verdict.violations.isEmpty()) {
            return FinishReconciliation(completion = action)
        }
        // Log every rule that objected — only violations.first() used to survive.
        verdict.violations.forEach { violation ->
            logger.warn("⚠ finish.report sid={} {}", sid, violation)
        }
        if (!config.finishGateCheck.equals("strict", ignoreCase = true)) {
            logger.warn(
                "⚠ finish.report sid={} finishGateCheck=warn — accepting the completion despite {} violation(s)",
                sid, verdict.violations.size,
            )
            return FinishReconciliation(completion = action)
        }

        val decision = finishReportRepair.decide(
            verdict,
            reportedFiles = action.filesChanged.orEmpty(),
            canRepair = allowRepair,
        )
        return when (decision.action) {
            FinishReportRepair.Action.PASSED -> FinishReconciliation(completion = action)

            FinishReportRepair.Action.RETRY -> {
                cliLoopTracer.logEvent(
                    "finish.repair.round",
                    mapOf(
                        "round" to decision.round,
                        "maxRounds" to finishReportRepair.maxRounds,
                        "missing" to verdict.unlistedFiles.take(20),
                    ),
                )
                logger.info(
                    "🔁 cli-agent.finish.repair.round sid={} round={}/{} missing={}",
                    sid, decision.round, finishReportRepair.maxRounds, verdict.unlistedFiles.size,
                )
                FinishReconciliation(repairMessage = decision.message)
            }

            FinishReportRepair.Action.AMEND -> {
                val amended = decision.amendedFiles.orEmpty()
                cliLoopTracer.logEvent(
                    "finish.amend",
                    mapOf(
                        "added" to verdict.unlistedFiles,
                        "filesChanged" to amended,
                    ),
                )
                logger.warn(
                    "⚠ finish.report sid={} amended filesChanged: the run wrote {} file(s) the " +
                        "report omitted ({} listed now) — the mutation trace is authoritative",
                    sid, verdict.unlistedFiles.size, amended.size,
                )
                FinishReconciliation(completion = action.copy(filesChanged = amended))
            }

            FinishReportRepair.Action.REJECT -> throw IllegalStateException(finishRejectionMessage(verdict))
        }
    }

    /** Bounded, actionable rejection message (the log has every violation). */
    private fun finishRejectionMessage(verdict: FinishReportGate.Verdict): String {
        val first = "Finish report rejected: ${verdict.violations.first()}"
        if (verdict.violations.size <= 1) return first
        return "$first (+${verdict.violations.size - 1} more violation(s) in the run log)"
    }

    /**
     * Record a validated completion: agent state, audit stream, trace and the
     * agent-data listing. Split from the gate so the CLI engine can repair a
     * report and only then accept it.
     */
    private suspend fun acceptCompletion(action: ActionDescription, context: ExecutionContext) {
        val step = context.step
        val sid = context.sessionId

        context.agentState.also {
            it.isComplete = true
            it.summary = action.summary
            it.keyFindings = action.keyFindings
            it.nextSuggestions = action.nextSuggestions
            it.gates = action.gates
            it.filesChanged = action.filesChanged
            it.problems = action.problems
            it.actionDescription = action
        }

        // Persist the completed state to the state audit stream. Without this, the final
        // "isComplete/summary" snapshot never reached state-history.jsonl — the last line
        // written there was the pre-completion state from updateAgentState.
        stateManager.writeAgentState(context.agentState, context.sessionId)

        logger.info("✅ task.complete sid={} step={} complete={}", sid.take(8), step, true)
        stateManager.addTrace(context.agentState, event = "complete", message = "#${step} complete")

        val files = fs.listOSFiles()
        if (files.isNotEmpty()) {
            logger.info("Agent data dir: \n{}", fs.dataDir.toUri())
            logger.info("Agent files: \n{}", files.joinToString("\n") { it.toUri().toString() })
        } else {
            logger.info("No files used by this agent")
        }
    }


    // ─── Finish-report evidence helpers ───────────────────────────────────────

    /**
     * Workspace whose git state backs the `filesChanged` cross-check.
     *
     * `-Dbrowser4.agent.finish.workspace=<dir>` wins; otherwise the agent's own
     * workspace (the tree its coding tools write into) is used — never the
     * server's process directory, which is a different, usually clean checkout.
     * See [resolveFinishGitWorkspace].
     */
    private fun finishWorkspaceRoot(): Path? = resolveFinishGitWorkspace(
        configured = config.finishWorkspace,
        agentWorkspace = runCatching { agentWorkspaceDir }.getOrNull(),
        processDir = Path.of(System.getProperty("user.dir")),
    )

    /**
     * Repository-relative changed paths from `git status --porcelain`.
     *
     * @return null when git is unavailable or the call fails — the check is
     *   skipped rather than failing a task because a host has no git.
     */
    /**
     * The `VERDICT_JSON: {...}` object cited in the report summary, if any.
     *
     * The report is asked to paste the verdict verbatim; extracting it here lets
     * the completion gate compare the citation with the verdict the run actually
     * recorded instead of trusting the prose.
     */
    private fun extractVerdictJson(summary: String?): String? {
        if (summary.isNullOrBlank()) return null
        val marker = "VERDICT_JSON:"
        val at = summary.indexOf(marker)
        if (at < 0) return null
        val rest = summary.substring(at + marker.length).trimStart()
        val start = rest.indexOf('{')
        if (start < 0) return null
        val end = rest.lastIndexOf('}')
        if (end <= start) return null
        return rest.substring(start, end + 1)
    }

    private fun gitChangedFiles(workspace: Path): Set<String>? =
        gitStatus(workspace)?.first

    /** Repository-relative DELETED paths (` D` / `D ` in porcelain), null when git is unavailable. */
    private fun gitDeletedFiles(workspace: Path): Set<String>? =
        gitStatus(workspace)?.second

    /**
     * `git status --porcelain` split into (changed, deleted).
     *
     * Deletions are tracked separately: a deleted file is absent from the
     * changed-file list, so a "deleted X" claim needs its own evidence.
     */
    private fun gitStatus(workspace: Path): Pair<Set<String>, Set<String>>? {
        return try {
            val process = ProcessBuilder("git", "-C", workspace.toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val changed = linkedSetOf<String>()
            val deleted = linkedSetOf<String>()
            text.lineSequence().forEach { line ->
                if (line.length < 4) return@forEach
                var path = line.substring(3).trim()
                // Rename/copy form: "old -> new" — the new path is what exists.
                if (" -> " in path) path = path.substringAfter(" -> ")
                val normalized = normalizeRepoPath(path)
                if (normalized.isEmpty()) return@forEach
                if (line.startsWith(" D") || line.startsWith("D ") || line.startsWith("DD")) {
                    deleted += normalized
                } else {
                    changed += normalized
                }
            }
            changed to deleted
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeRepoPath(raw: String): String =
        raw.trim().trim('"').replace('\\', '/').removePrefix("./").removePrefix("/")

    // ─── Repair loop (plan P1.3) ──────────────────────────────────────────────

    /**
     * Keep the newest structured verdict from this round's tool results.
     *
     * Only results that actually carry a verdict are considered: the
     * `VERDICT_JSON:` line of the human rendering, or the pure-JSON reply of
     * `coding.verify(json=true)`.
     */
    private fun recordVerificationVerdict(records: List<RoundToolRecord>) {
        val candidate = records.lastOrNull { record ->
            record.resultText.contains(VerificationReport.VERDICT_PREFIX) ||
                (record.name.contains("verify", ignoreCase = true) &&
                    record.resultText.trimStart().startsWith("{"))
        } ?: return
        VerificationReport.parseFromText(candidate.resultText)?.let { lastVerificationVerdict = it }
    }

    /**
     * The repair message to inject, or null when the completion may proceed.
     *
     * A failing verdict spends one repair round and returns the bounded
     * feedback (diagnostics included) as a user message. When the budget is
     * spent while the verdict still fails, the task FAILS with the verdict as
     * evidence — an exhausted budget is never a silent success (plan P1.3).
     */
    private fun nextRepairMessage(context: ExecutionContext): String? {
        val verdict = lastVerificationVerdict ?: return null
        val decision = repairLoop.decide(verdict)
        return when (decision.action) {
            RepairLoop.Action.RETRY -> {
                val reason = verdict.failureReason() ?: "verification did not pass"
                cliLoopTracer.logEvent(
                    "repair.round",
                    mapOf("round" to decision.round, "maxRounds" to repairLoop.maxRounds, "reason" to reason),
                )
                logger.info(
                    "🔁 cli-agent.repair.round sid={} round={}/{} reason={}",
                    context.sid, decision.round, repairLoop.maxRounds, reason,
                )
                decision.message
            }

            RepairLoop.Action.EXHAUSTED -> throw IllegalStateException(
                "Finish report rejected: verification still fails after " +
                    "${repairLoop.roundsUsed} repair round(s) — ${verdict.failureReason()}"
            )

            RepairLoop.Action.PASSED, RepairLoop.Action.DISABLED -> null
        }
    }

    // ─── Final act result & summary ───────────────────────────────────────────
    private suspend fun buildFinalActResult(
        instruction: String, cxtIn: ExecutionContext, startTime: Instant, stopReason: StopReason? = null
    ): ActResult {
        val executionTime = Duration.between(startTime, Instant.now())

        logger.info("✅ agent.done sid={} steps={} dur={} stopReason={}", cxtIn.sid, cxtIn.step, executionTime, stopReason)

        val result = generateFinalSummary(instruction, cxtIn)

        val summary = result.modelResponse
        val context = result.context
        val ok = summary.state != ResponseState.OTHER
        val exception = when {
            // The loop terminated abnormally (no-op limit / max steps) while the task was
            // NOT complete: report failure so supervisors can distinguish success from abort.
            stopReason != null && stopReason != StopReason.COMPLETED -> IllegalStateException(
                "Agent loop stopped abnormally: $stopReason at step ${cxtIn.step}"
            )

            ok -> null
            else -> IllegalStateException("ResponseState: OTHER")
        }

        return ActResult(
            message = summary.content,
            action = context.instruction,
            result = context.agentState.toolCallResult,
            exception = exception
        )
    }

    private suspend fun generateFinalSummary(instruction: String, context: ExecutionContext): SummarizeResult {
        return try {
            val result = summarize(instruction, context)
            stateManager.addTrace(
                context.agentState,
                event = "final",
                items = mapOf("summaryPreview" to result.modelResponse.content.take(200)),
                message = "🧾 FINAL"
            )
            transcriptPersister.persist(instruction, result.modelResponse, context)
            result
        } catch (e: Exception) {
            logger.error("📝❌ agent.summary.fail sid={} msg={}", context.sid, e.message, e)
            SummarizeResult(
                context = context, ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }

    private suspend fun summarize(goal: String, ctxIn: ExecutionContext): SummarizeResult {
        val step = ctxIn.step + 1
        val context = stateManager.buildExecutionContext(goal, step, event = "summary", baseContext = ctxIn)
        stateManager.setActiveContext(context)

        return try {
            val (system, user) = promptBuilder.buildSummaryPrompt(goal, stateHistory)
            slogger.info("📝⏳ Generating final summary", context)
            val response = cta.chatModel.callUmSm(user, system)
            slogger.info(
                "📝✅ Summary generated successfully",
                context,
                mapOf("responseLength" to response.content.length, "responseState" to response.state)
            )
            SummarizeResult(context, response)
        } catch (e: Exception) {
            slogger.logError("📝❌ Summary generation failed", e, context.sessionId)
            SummarizeResult(
                context, modelResponse = ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }
}

