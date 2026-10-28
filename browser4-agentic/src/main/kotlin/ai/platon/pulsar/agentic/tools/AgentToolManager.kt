package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.common.AgentShell
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.CodingComponents
import ai.platon.pulsar.coding.CodingWorkspace
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import ai.platon.pulsar.agentic.tools.builtin.*
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.chrome.PulsarWebDriver
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class AgentToolManager constructor(
    val baseDir: Path,
    val agent: BasicBrowserAgent,
    initialWorkspaceRoot: Path = CodingWorkspace.workspaceRoot,
) {
    private val logger = getLogger(AgentToolManager::class)

    /** The active workspace root. Rebindable via [rebindWorkspace] for
     *  `agent run --fresh-workspace`; [codingShell], [codingFs] and
     *  [b4CliExecutor] are rebuilt to point at the new root. */
    @Volatile
    var workspaceRoot: Path = initialWorkspaceRoot
        private set

    /**
     * The access policy for the coding domain.
     *
     * Replaced per task by [applyAccessPolicy] (from `command.run(access=…)`) and restored by
     * [resetAccessPolicy] when the task ends: the companion agent is shared across tasks, so a
     * previous task's grant must never leak into the next one.
     */
    @Volatile
    var accessPolicy: AgentAccessPolicy = AgentAccessPolicy.serverDefault
        private set

    /** Upper bound for polling document.readyState after a navigation-triggering action. */
    private val navigationPollTimeoutMillis = 30_000L

    /** Interval between document.readyState polls while waiting for a navigation. */
    private val navigationPollIntervalMillis = 200L

    /**
     * Timeout for waiting on the body element once the document became ready.
     * The body must already exist at that point, so a short budget is enough —
     * the poll above already consumed the main navigation timeout.
     */
    private val navigationDomReadyTimeoutMillis = 10_000L

    /**
     * Custom tool targets registry, mapping domain names to their corresponding target objects.
     * Users can register custom targets here for their custom tool executors.
     */
    private val _customTargets = mutableMapOf<String, Any>()

    val session: AgenticSession get() = agent.session
    val driver: WebDriver get() = session.getOrCreateBoundDriver()
    val fs: AgentFileSystem = AgentFileSystem(baseDir)
    val shell: AgentShell = AgentShell(baseDir)

    /** Enhanced coding shell for dev tools (git, cargo, mvn, npm, etc.) */
    var codingShell: CodingAgentShell = buildCodingShell(initialWorkspaceRoot)
        private set
    /** Enhanced coding file system for full filesystem access */
    var codingFs: CodingAgentFileSystem = buildCodingFs(initialWorkspaceRoot)
        private set
    /** Composite target for the coding domain. Rebuilt alongside the shell/fs
     *  on [rebindWorkspace] so tool dispatch always targets the active root. */
    var codingTarget: CodingToolExecutor.Target = buildCodingTarget(initialWorkspaceRoot)
        private set
    /** CLI tool executor for browser4-cli integration (domain `b4`) */
    var b4CliExecutor: B4CliToolExecutor = buildB4CliExecutor(initialWorkspaceRoot)
        private set

    val system: SystemToolExecutor = SystemToolExecutor(this)

    val skillContext: SkillContext by lazy {
        SkillContext(
            sessionId = agent.uuid.toString(),
            sharedResources = mutableMapOf(
                "session" to session,
                "agent" to agent,
                "tab" to driver,
                "driver" to driver,
            ),
        )
    }
    val skillTarget: SkillToolTarget by lazy { SkillToolTarget(skillContext, SkillRegistry.instance) }
    val skills: SkillToolExecutor = SkillToolExecutor()

    /**
     * Domain alias are mainly used for backward capability
     * */
    val domainAlias = mapOf(
        "tab" to "tab",
        "Tab" to "tab",
        "driver" to "tab",
        "WebDriver" to "tab",

        "fs" to "fs",
        "AgentFileSystem" to "fs",
        "FileSystem" to "fs",

        "shell" to "shell",
        "AgentShell" to "shell",

        "captcha" to "captcha",
        "Captcha" to "captcha",

        "coding" to "coding",
        "Coding" to "coding",
        "dev" to "coding",

        "cli" to "b4",
        "Cli" to "b4",
        "browser4-cli" to "b4",
        "b4" to "b4",
    )

    private val _concreteExecutors: MutableMap<String, ToolExecutor> by lazy {
        listOf(
            BrowserTabToolExecutor(),
            BrowserToolExecutor(),
            AgentToolExecutor(),
            CodingToolExecutor(),
            b4CliExecutor,
            system,
            skills
        ).associateBy { it.domain }.toMutableMap()
    }

    val executor by lazy { BasicToolCallExecutor(_concreteExecutors) }

    val registeredExecutors: Map<String, ToolExecutor> get() = executor.toolExecutors

    val customTargets: Map<String, Any> get() = _customTargets

    /**
     * Callback invoked after every successful tool execution (including tools
     * executed inside the native tool-calling loop). The owning agent uses it
     * to count inner-loop executions for the finish-report false-completion
     * guard (design §3.2).
     */
    var toolExecutionRecorder: ((domain: String, method: String, resultText: String?) -> Unit)? = null

    /** Notify the recorder (if wired) about one executed tool call. */
    fun notifyToolExecuted(domain: String, method: String, resultText: String? = null) {
        toolExecutionRecorder?.invoke(domain, method, resultText)
    }

    /** Cancel all tracked CLI background jobs (agent close). */
    fun closeCliJobs() {
        b4CliExecutor.closeJobs()
    }

    init {
        // Self-development runs (coding mode in the CLI tool loop) execute
        // repository-relative paths inside this agent's scratch workspace. Mirror
        // the checkout's reactor there ONCE, before the first tool call, so
        // Maven resolves modules and the agent never has to repair workspace
        // topology itself (observed: an agent ran `mklink /J` for every module
        // and deleted/re-created a junction mid-run).
        provisionCodingWorkspaceMirror()

        // Register coding and b4 (browser4-cli) tool specs so they appear in the LLM prompt.
        // The ToolCallSpecificationRenderer merges these dynamically-registered
        // specs alongside the hardcoded ToolSpecification.TOOL_CALL_SPECIFICATION.
        ToolCallSpecificationRenderer.registerBuiltinDomainSpecs(
            "coding",
            CodingToolExecutor().getToolSpecs().values.toList()
        )
        ToolCallSpecificationRenderer.registerBuiltinDomainSpecs(
            "b4",
            b4CliExecutor.getToolSpecs().values.toList()
        )
    }

    /**
     * Link the checkout's reactor modules into this agent's scratch workspace
     * when the agent runs in coding mode (self-development).
     *
     * A workspace that is already a checkout (the default coding root) and a
     * workspace with no detectable source checkout are left untouched, so
     * ordinary browser sessions pay nothing.
     *
     * Idempotent, and called both from the constructor (cheap: it skips an
     * already-mirrored workspace) and again right before the CLI loop starts —
     * see [ai.platon.pulsar.agentic.agents.RobustBrowserAgent]: the constructor
     * may run before the task runner sets `codingMode`, which used to leave a
     * coding run with an empty working tree.
     */
    internal fun provisionCodingWorkspaceMirror() {
        if (!agent.codingMode) return
        val root = workspaceRoot.toAbsolutePath().normalize()
        if (root == ai.platon.pulsar.coding.CodingWorkspace.workspaceRoot.toAbsolutePath().normalize()) return
        runCatching {
            val report = ai.platon.pulsar.coding.WorkspaceMirror.ensure(root)
            if (report.sourceRepo != null) {
                logger.info("+ Coding workspace mirror: {}", report.summary())
            }
            // Write point 1: create the manifest if absent (§6.1). We never
            // overwrite an existing manifest here — the next task's write points
            // own state/currentTask/lastTask.
            val store = ai.platon.pulsar.coding.WorkspaceManifestStore()
            if (store.read(root) == null) {
                store.write(root, ai.platon.pulsar.coding.WorkspaceManifest(
                    workspaceId = agent.uuid.toString(),
                    agentUuid = agent.uuid.toString(),
                    engine = "cli",
                    sourceRepo = report.sourceRepo?.toString(),
                ))
            }
        }.onFailure { e ->
            logger.warn("Could not provision the coding workspace mirror for {}: {}", root, e.message)
        }
    }

    /**
     * Task-start lifecycle for the agent workspace (§6.2).
     *
     * - archives the previous task's deliverables and resets the file system's
     *   change tracking so this task opens with `changeSummary=0`;
     * - writes the manifest's `state=active` and `currentTask` (write point 2),
     *   rotating any leftover `currentTask` to `lastTask` first;
     * - captures the git baseline for the end-of-task Δgit computation;
     * - returns the "inherited workspace state" prompt block (or null).
     *
     * Safe to call for non-coding tasks too: it only resets the mutation log and
     * records `coding=false` in the manifest.
     */
    fun beginTask(taskId: String, instruction: String, coding: Boolean): String? {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val store = ai.platon.pulsar.coding.WorkspaceManifestStore()
        val lifecycle = ai.platon.pulsar.coding.WorkspaceTaskLifecycle()
        val existing = runCatching { store.read(root) }.getOrNull()
        val sourceRepo = existing?.sourceRepo?.let { java.nio.file.Path.of(it) }
        // Write point 2: rotate leftover currentTask (crash recovery) then set active.
        runCatching {
            val base = existing ?: ai.platon.pulsar.coding.WorkspaceManifest(
                workspaceId = agent.uuid.toString(),
                agentUuid = agent.uuid.toString(),
                engine = "cli",
                sourceRepo = sourceRepo?.toString(),
            )
            val rotatedLast = base.currentTask ?: base.lastTask
            val current = ai.platon.pulsar.coding.TaskSnapshot(
                taskId = taskId,
                instruction = instruction.take(ai.platon.pulsar.coding.WorkspaceManifest.MAX_INSTRUCTION_LEN),
                coding = coding,
                startedAt = java.time.Instant.now(),
                baselineCaptured = sourceRepo != null,
            )
            store.write(root, base.copy(
                state = ai.platon.pulsar.coding.WorkspaceState.ACTIVE,
                currentTask = current,
                lastTask = rotatedLast,
            ))
            // Capture the git baseline for Δgit at task end.
            if (sourceRepo != null) {
                gitBaseline = lifecycle.captureGitBaseline(sourceRepo)
            }
        }.onFailure { logger.warn("beginTask manifest write failed: {}", it.message) }
        return runCatching { lifecycle.beginTask(root, taskId, instruction, coding, codingFs) }
            .onFailure { logger.warn("beginTask archive/reset failed: {}", it.message) }
            .getOrNull()?.inheritedSummary
    }

    /**
     * Task-end lifecycle (write point 3, §6.1): fill the current task snapshot
     * with terminal status, filesChanged (Δgit ∪ mutation) and mutation count,
     * then rotate it to `lastTask` and clear `currentTask`.
     *
     * Must be called from a `finally` (non-cancellable) so cancelled tasks are
     * still recorded.
     */
    fun endTask(taskId: String, status: ai.platon.pulsar.coding.TaskStatus) {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val store = ai.platon.pulsar.coding.WorkspaceManifestStore()
        val lifecycle = ai.platon.pulsar.coding.WorkspaceTaskLifecycle()
        runCatching {
            val m = store.read(root) ?: return@runCatching
            val cur = m.currentTask ?: return@runCatching
            val sourceRepo = m.sourceRepo?.let { java.nio.file.Path.of(it) }
            val mutations = codingFs.mutationsSnapshot().size
            val deliverables = codingFs.mutationsSnapshot().map { it.path }.toSet()
            val filesChanged = lifecycle.computeFilesChanged(sourceRepo, gitBaseline, deliverables)
            val terminal = cur.copy(
                status = status,
                endedAt = java.time.Instant.now(),
                filesChanged = filesChanged,
                mutations = mutations,
            )
            store.write(root, m.copy(
                state = when (status) {
                    ai.platon.pulsar.coding.TaskStatus.COMPLETE -> ai.platon.pulsar.coding.WorkspaceState.COMPLETE
                    ai.platon.pulsar.coding.TaskStatus.CANCELLED -> ai.platon.pulsar.coding.WorkspaceState.CANCELLED
                    ai.platon.pulsar.coding.TaskStatus.FAILED -> ai.platon.pulsar.coding.WorkspaceState.FAILED
                },
                currentTask = null,
                lastTask = terminal,
            ))
            gitBaseline = emptySet()
        }.onFailure { logger.warn("endTask manifest write failed: {}", it.message) }
    }

    /** Git status baseline captured at task start; cleared on task end. */
    private var gitBaseline: Set<String> = emptySet()

    // -- workspace rebinding (fresh-workspace) -----------------------------------

    private fun buildCodingShell(root: Path) = CodingComponents.shell(root, accessPolicy)

    private fun buildCodingFs(root: Path) = CodingComponents.fs(root, accessPolicy, persistSnapshots = true)

    /** Composite target carrying the active policy; the executor enforces it on every dispatch. */
    private fun buildCodingTarget(root: Path): CodingToolExecutor.Target {
        val shell = codingShell
        return CodingToolExecutor.Target(
            shell = shell,
            fs = codingFs,
            policy = accessPolicy,
            buildShell = CodingComponents.buildShell(root, accessPolicy, modelShell = shell),
        )
    }

    private fun buildB4CliExecutor(root: Path) = B4CliToolExecutor(
        backendBaseUrl = session.sessionConfig.get("browser4.server.url"),
        defaultWorkingDir = root,
        // Per-task policy: `b4.run`'s file:// and upload must obey the same
        // boundary as the coding tools (read lazily — the policy changes per task).
        accessPolicy = { accessPolicy },
    )

    /**
     * Point the coding tools at a fresh, isolated workspace root. Used by the
     * task runner when the caller passes `agent run --fresh-workspace`. The
     * previous [codingShell]/[codingFs]/[b4CliExecutor] are replaced; callers
     * that read these properties each dispatch see the new instances.
     */
    fun rebindWorkspace(root: Path) {
        workspaceRoot = root
        codingShell = buildCodingShell(root)
        codingFs = buildCodingFs(root)
        codingTarget = buildCodingTarget(root)
        b4CliExecutor = buildB4CliExecutor(root)
        logger.info("rebound agent workspace to {}", root)
    }

    /**
     * Apply the access policy for the current task.
     *
     * Rebuilds the coding shell/fs/target so the new preset is not merely recorded but enforced:
     * a read-only file system and a disabled or narrowed shell are created here, while the tool
     * registry filter and the executor's method gate read [accessPolicy] directly.
     */
    fun applyAccessPolicy(policy: AgentAccessPolicy) {
        if (policy == accessPolicy) return
        accessPolicy = policy
        codingShell = buildCodingShell(workspaceRoot)
        codingFs = buildCodingFs(workspaceRoot)
        codingTarget = buildCodingTarget(workspaceRoot)
        logger.info(
            "agent access policy: preset={}, denyTools={}", policy.preset.id, policy.denyTools
        )
    }

    /** Restore the server-wide default policy after a task ends. */
    fun resetAccessPolicy() = applyAccessPolicy(AgentAccessPolicy.serverDefault)

    /**
     * Register a custom target object for a specific domain.
     * The target will be used when executing tool calls for the given domain.
     *
     * @param domain The domain name for the custom tool.
     * @param target The target object to be used by the custom tool executor.
     */
    fun registerCustomTarget(domain: String, target: Any) {
        val oldTarget = _customTargets[domain]
        _customTargets[domain] = target
        if (oldTarget != target) {
            logger.info("+ Registered custom target for domain: {}", domain)
        }
    }

    /**
     * Unregister a custom target for a specific domain.
     *
     * @param domain The domain to unregister.
     * @return true if a target was removed, false otherwise.
     */
    fun unregisterCustomTarget(domain: String): Boolean {
        val removed = _customTargets.remove(domain)
        if (removed != null) {
            logger.info("+ Unregistered custom target for domain: {}", domain)
            return true
        }
        return false
    }

    fun hasCustomTarget(domain: String): Boolean {
        return _customTargets.containsKey(domain)
    }

    fun registerCustomToolExecutor(executor: ToolExecutor) {
        val domain = executor.domain
        val oldExecutor = _concreteExecutors[domain]
        _concreteExecutors[domain] = executor
        if (oldExecutor != executor) {
            logger.info("+ Registered custom tool executor for domain: {}", domain)
        }
    }

    fun unregisterCustomToolExecutor(domain: String): Boolean {
        val removed = _concreteExecutors.remove(domain)
        if (removed != null) {
            logger.info("+ Unregistered custom tool executor for domain: {}", domain)
            return true
        }
        return false
    }

    fun hasToolExecutor(domain: String): Boolean {
        return _concreteExecutors.containsKey(domain)
    }

    fun help(domain: String, method: String): String {
        // Check built-in executors first
        val builtInHelp = registeredExecutors.values.firstOrNull { it.domain == domain }?.help(method)
        if (builtInHelp != null) {
            return builtInHelp
        }

        // Check custom executors
        val customExecutor = CustomToolRegistry.instance.get(domain)
        return customExecutor?.help(method) ?: ""
    }

    fun normalizeToolCall(tc: ToolCall): ToolCall {
        val normalizedDomain = normalizeDomain(tc.domain)
        val spec = getToolSpec(normalizedDomain, tc.method) ?: getToolSpec(tc.domain, tc.method)
        val normalizedArguments = normalizeArguments(normalizedDomain, tc.method, tc.arguments, spec)

        if (normalizedDomain == tc.domain && normalizedArguments == tc.arguments) {
            return tc
        }

        return tc.copy(domain = normalizedDomain, arguments = normalizedArguments)
    }

    /**
     * Returns all tool specifications from all concrete executors, grouped by domain.
     *
     * @return A map from domain name to a map of method name to [ToolSpec].
     */
    fun getAllToolSpecs(): Map<String, Map<String, ToolSpec>> {
        return registeredExecutors.values.associate { executor -> executor.domain to executor.getToolSpecs() }
    }

    /**
     * Returns all exposed [ToolSpec]s as a flat list (built-in + custom registry).
     */
    fun getAllExposedToolSpecs(): List<ToolSpec> {
        return registeredExecutors.values.flatMap { it.getToolSpecs().values } +
            CustomToolRegistry.instance.getAllToolCallSpecifications()
    }

    /**
     * Returns LangChain4j [ToolSpecification]s for native tool calling.
     *
     * @see ToolCallSpecificationRenderer.collectAllToolSpecs
     */
    fun getLangChain4jToolSpecifications(): List<dev.langchain4j.agent.tool.ToolSpecification> {
        return ToolCallSpecificationRenderer.collectAllToolSpecs().let {
            ToolSpecificationConverter.toToolSpecifications(it)
        }
    }

    /**
     * Returns a reverse registry mapping LC4j tool names → [ToolSpec]
     * for decoding [ToolExecutionRequest]s.
     */
    fun getLangChain4jToolRegistry(): Map<String, ToolSpec> {
        return ToolCallSpecificationRenderer.collectAllToolSpecs().let {
            ToolSpecificationConverter.toRegistry(it)
        }
    }

    /**
     * Returns the tool specification for a specific domain and method, or null if not found.
     *
     * @param domain The tool domain (e.g. "tab", "fs").
     * @param method The method name within the domain.
     * @return The [ToolSpec] for the given domain and method, or null.
     */
    fun getToolSpec(domain: String, method: String): ToolSpec? {
        return registeredExecutors.values.find { it.domain == domain }?.getToolSpecs()?.get(method)
    }

    /**
     * Execute a tool call directly, bypassing the full [ActionDescription] lifecycle.
     *
     * This is the lightweight entry point for callers (e.g. [Browser4MCPServer]) that already
     * know the domain, method, and arguments and do not need agent-state tracking or
     * post-navigation hooks.
     *
     * @param tc The tool call to execute.
     * @return A [TcEvaluate] with the execution result or exception.
     */
    @Throws(UnsupportedOperationException::class)
    suspend fun execute(tc: ToolCall): ToolCallResult {
        val normalized = normalizeToolCall(tc)
        var topDomain = normalized.domain.split(".").first()
        topDomain = domainAlias.getOrDefault(topDomain, topDomain)
        val evaluate = when (topDomain) {
            "tab" -> executor.callFunctionOn(normalized, driver)
            "browser" -> {
                // A closeTab without index/tabId means "close the current tab".
                // The user-visible current tab is browser.frontDriver (tab-list
                // marks it active); browser.frontDriver is not reliably
                // maintained (it dangles after the previously active tab is
                // destroyed and depends on the bringToFront CDP round-trip),
                // so fall back to the session-bound driver and finally to the
                // first live driver.  Each candidate is validated against the
                // driver map — closing a dangling driver is a silent no-op
                // that leaves every tab open.
                val resolved = if (normalized.method == "closeTab" && !targetsSpecificTab(normalized.arguments)) {
                    val browser = (driver as? AbstractWebDriver)?.browser
                        ?: session.boundBrowser
                    val target = (browser as? AbstractBrowser)?.let { b ->
                        (b.frontDriver as? AbstractWebDriver)
                            ?.takeIf { b.drivers.containsKey(it.guid) }
                            ?: (driver as? AbstractWebDriver)
                            ?.takeIf { b.drivers.containsKey(it.guid) }
                            ?: b.listDrivers().firstOrNull()
                    }
                    if (target != null) {
                        normalized.copy(
                            arguments = (normalized.arguments + ("tabId" to target.guid)).toMutableMap()
                        )
                    } else {
                        throw IllegalArgumentException("No browser tabs are currently open")
                    }
                } else normalized
                executor.callFunctionOn(resolved, driver.browser)
            }
            "fs" -> executor.callFunctionOn(normalized, fs)
            "shell" -> executor.callFunctionOn(normalized, shell)
            "agent" -> executor.callFunctionOn(normalized, agent)
            "coding" -> executor.callFunctionOn(normalized, codingTarget)
            "b4" -> executor.callFunctionOn(normalized, codingShell)
            "command" -> {
                // TODO: the commandTarget is ai.platon.pulsar.agentic.tools.advanced.CommandRunner, consider make it built-in
                //      and is registered in browser4-rest module
                val commandTarget = _customTargets["command"]
                    ?: throw UnsupportedOperationException(
                        "Command domain '${normalized.domain}' requires a registered CommandRunner target."
                    )
                // The command executor lives in CustomToolRegistry (browser4-rest), not
                // among the built-in executors, so it must be dispatched directly —
                // routing the target through the composite executor only produced
                // "Unsupported receiver class UserCommandExecutor". Same pattern as
                // the captcha branch below.
                val commandExecutor = CustomToolRegistry.instance.get(normalized.domain)
                if (commandExecutor != null) {
                    commandExecutor.callFunctionOn(normalized, commandTarget)
                } else {
                    executor.callFunctionOn(normalized, commandTarget)
                }
            }
            "system" -> executor.callFunctionOn(normalized, system)
            "skill" -> executor.callFunctionOn(normalized, skillTarget)
            "captcha" -> {
                val captchaExecutor = CustomToolRegistry.instance.get(normalized.domain)
                    ?: throw UnsupportedOperationException("Captcha domain is active but no CaptchaToolExecutor registered in CustomToolRegistry")
                captchaExecutor.callFunctionOn(normalized, driver)
            }
            else -> {
                val customExecutor = CustomToolRegistry.instance.get(normalized.domain)
                if (customExecutor != null) {
                    // Resolve the receiver by the executor's declared receiverClass:
                    // plugin tools operating on the current page (WebDriver receiver)
                    // get the session-bound driver — the same receiver the captcha
                    // branch passes. Domains with an explicitly registered target
                    // (e.g. "command") fall back to the custom-target registry.
                    val target = when {
                        customExecutor.receiverClass == WebDriver::class -> driver
                        else -> _customTargets[normalized.domain]
                    }
                    when {
                        target != null -> customExecutor.callFunctionOn(normalized, target)
                        // Service-backed executors resolve their own collaborators, so
                        // a missing target is not a failure for them.
                        !customExecutor.requiresReceiver -> {
                            logger.debug(
                                "Custom domain '{}' has no registered target; {} does not consume a receiver",
                                normalized.domain, customExecutor::class.simpleName
                            )
                            customExecutor.callFunctionOn(normalized, Any())
                        }
                        else -> throw UnsupportedOperationException(
                            "Custom domain '${normalized.domain}' is registered but no target object is available."
                        )
                    }
                } else {
                    throw UnsupportedOperationException("Unsupported domain: ${normalized.domain}")
                }
            }
        }

        return onDidToolCall(tc, evaluate)
    }

    private suspend fun onDidToolCall(
        tc: ToolCall, evaluate: TcEvaluate, message: String? = null
    ): ToolCallResult {
        val tcResult = ToolCallResult(
            evaluate = evaluate,
            message = message,
        )

        val method = tc.method
        when (method) {
            "switchTab" -> onDidSwitchTab(tc, evaluate)
            "closeTab" -> onDidCloseTab()
            "navigate" -> onDidNavigate(driver, tc, evaluate)
        }

        return tcResult
    }

    /**
     * Handle switching to a new tab by binding the target driver to the session.
     *
     * The driver returned by [BrowserToolExecutor.switchTab] is wrapped into a
     * description map by [AbstractToolExecutor.callFunctionOn] (WebDriver is not
     * a serializable scalar), so [evaluate.value] can never be a WebDriver.
     * Resolve the target driver from the tool-call arguments (`tabId` or
     * `index`) against [session.boundBrowser] instead; fall back to the
     * browser's front driver when neither can be resolved.
     */
    private suspend fun onDidSwitchTab(tc: ToolCall, evaluate: TcEvaluate) {
        val switchedDriver = evaluate.value as? WebDriver
        if (switchedDriver != null) {
            bindSwappedDriver(switchedDriver)
            return
        }

        val browser = session.boundBrowser
        if (browser == null) {
            logger.warn("! switchTab did not return a WebDriver and no browser is bound")
            return
        }

        // BrowserToolExecutor.switchTab returns the resolved tab's GUID so the
        // session binds the exact driver the executor brought to front.  The
        // driver itself is not serializable, and re-resolving from `index`
        // would hit listDrivers() again — whose iteration order is unstable
        // (ConcurrentHashMap) and can differ from the resolver's earlier call,
        // silently binding a different tab than the one that was switched to.
        val returnedGuid = (evaluate.value as? Map<*, *>)?.get("guid")?.toString()
        if (!returnedGuid.isNullOrBlank()) {
            val resolved = browser.drivers[returnedGuid]
            if (resolved != null) {
                bindSwappedDriver(resolved)
                return
            }
            logger.warn("! switchTab returned guid {} but no driver with that guid is registered", returnedGuid)
        }

        // Resolve from tabId (GUID) or index — the same arguments that
        // BrowserToolExecutor.resolveTabDriver uses.
        val tabId = tc.arguments["tabId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val index = (tc.arguments["index"] as? Number)?.toInt()
            ?: tc.arguments["index"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val resolved = when {
            tabId != null -> browser.drivers[tabId]
            // The same stable creation order BrowserToolExecutor.listTabs numbers
            // and resolveTabDriver resolves, so the index the caller read from a
            // listing addresses the tab it was switched to.
            index != null -> TabActivity.orderedTabDrivers(browser).getOrNull(index)
            else -> null
        }
        if (resolved != null) {
            bindSwappedDriver(resolved)
            return
        }

        // Last resort: bind whatever the browser reports as front.  Note that
        // bringToFront() may not have committed the switch yet, so this can
        // still bind the previous tab — log it for diagnosis.
        logger.warn("! switchTab resolved no driver (tabId={}, index={}); falling back to frontDriver", tabId, index)
        val fallback = browser.frontDriver
        if (fallback == null) {
            logger.warn("! No driver is in front after switchTab")
            return
        }
        bindSwappedDriver(fallback)
    }

    /**
     * Bind [driver] to the session, swapping it to a [Browser4WebDriver] first
     * when it is a plain [PulsarWebDriver].
     *
     * The session's bean registry keys beans by their concrete class name, and
     * [session.boundDriver] returns the *first* bean assignable to WebDriver in
     * insertion order.  Browser tabs created by PulsarBrowser are plain
     * PulsarWebDriver instances, while the session's bound driver is a
     * Browser4WebDriver.  Binding the raw tab driver would therefore add a
     * second WebDriver bean that boundDriver never sees — the old driver keeps
     * winning.  The Browser4WebDriver.from swap is a pure binding replacement
     * (same chromeTab, BrowserProtocol, and browser), so the swapped driver
     * replaces the existing bean and the session follows the switch.
     */
    private suspend fun bindSwappedDriver(driver: WebDriver) {
        val bound = when {
            driver is Browser4WebDriver -> driver
            driver is PulsarWebDriver -> Browser4WebDriver.from(driver)
            else -> driver
        }
        session.bindDriver(bound)

        // A swapped driver starts without an isolated-world context cache: it
        // was created *after* the tab's document committed, so neither its own
        // navigation nor the frame-navigated event registered the Browser4
        // runtime (__pulsar_utils__) on this tab.  Evaluations would fall back
        // to the main world, where the runtime never exists, and capture
        // helpers would throw a ReferenceError until the next navigation.
        // Initialize the runtime right away so the first capture on a
        // tab-new target works; best-effort — the capture-side guard in
        // InteractiveBrowserEmulator.ensurePulsarUtils retries lazily when
        // this attempt races an in-flight navigation.
        if (bound is Browser4WebDriver) {
            runCatching { bound.ensurePulsarUtilsInjected() }.onFailure {
                logger.debug("Failed to initialize the dual-world runtime after binding driver {}", it.message)
            }
        }
    }

    /**
     * Handle tab close by rebinding the session to the next available tab driver.
     *
     * After [BrowserToolExecutor.closeTab] calls [Browser.destroyDriver], the closed
     * driver is removed from the browser's driver map but the session may still hold a
     * stale reference via [session.boundDriver].  If the closed tab was the currently
     * bound driver, subsequent operations (including the CLI's post-command snapshot)
     * would operate on a dead driver.
     *
     * This handler rebinds the session to the current front-most driver so that
     * follow-up calls see the correct page.
     */
    private suspend fun onDidCloseTab() {
        val browser = session.boundBrowser
        val oldBoundDriver = session.boundDriver ?: return
        val remainingDrivers = browser?.listDrivers().orEmpty()

        // If the old bound driver is still present in the browser's driver list, the
        // tab was not actually destroyed (edge case or destroyDriver was a no-op).
        // In that case there is nothing to rebind.
        if (oldBoundDriver in remainingDrivers) {
            return
        }

        val newFront = remainingDrivers.firstOrNull()
        if (newFront == null) {
            logger.warn("! All tabs closed — no driver to rebind after closeTab")
            session.unbindDriver(oldBoundDriver)
            return
        }

        // destroyDriver never clears frontDriver, so it dangles whenever the front
        // tab was the one closed.  Repair it to the new front so that listTabs'
        // active flag and a targetless closeTab stay coherent.  The Browser
        // interface only exposes frontDriver as a getter, hence the cast.
        val abstractBrowser = browser as? AbstractBrowser
        val oldGuid = (oldBoundDriver as? AbstractWebDriver)?.guid
        if (abstractBrowser != null && oldGuid != null &&
            (abstractBrowser.frontDriver as? AbstractWebDriver)?.guid == oldGuid
        ) {
            abstractBrowser.frontDriver = newFront
        }

        bindSwappedDriver(newFront)
        logger.info("👀 Session driver rebound after closeTab: {} -> {}",
            oldBoundDriver, newFront)
    }

    /**
     * True when [arguments] already targets a specific tab for closeTab,
     * i.e. carries a non-empty `index` or `tabId`.
     */
    private fun targetsSpecificTab(arguments: Map<String, Any?>): Boolean {
        val index = arguments["index"]
        if (index is Number) return true
        if (index is String && index.trim().isNotEmpty()) return true
        return !arguments["tabId"]?.toString()?.trim().isNullOrEmpty()
    }

    /**
     * TODO: add an option to driver.navigate() to wait
     * */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun onDidNavigate(driver: WebDriver, toolCall: ToolCall, evaluate: TcEvaluate) {
        // waitForNavigation() must not be used here:
        // - the no-arg overload's predicate is `"" != currentUrl()`, which is true as soon
        //   as the page has any URL — it returns immediately without waiting at all;
        // - the oldUrl overload's predicate is `oldUrl != currentUrl()`, which can never
        //   become true for a same-URL navigation (SPA route, fragment jump, same-URL
        //   goto) — it silently burns the whole timeout.
        // Poll document.readyState until 'complete' instead, which covers both
        // URL-changing and same-URL navigations. Best-effort: if the eval fails
        // (e.g. the page context is wedged), fall back to a short settle delay.
        var sawComplete = false
        try {
            val deadline = System.currentTimeMillis() + navigationPollTimeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val state = driver.evaluateValue("document.readyState") as? String
                if (state == "complete") {
                    sawComplete = true
                    break
                }
                delay(navigationPollIntervalMillis)
            }
        } catch (e: Exception) {
            logger.debug("onDidNavigate: exception while polling readyState: ${e.message}")
        }

        if (sawComplete) {
            // The document became ready. The body must already exist — a short
            // DOM-ready budget is enough. Do NOT call the no-timeout
            // waitForSelector("body") overload: its default timeout is 60s, and
            // stacking it on top of the exhausted poll doubles the dead time
            // when the body never appears.
            driver.waitForSelector("body", navigationDomReadyTimeoutMillis)
        } else {
            // The document never became ready (e.g. the page context is
            // wedged and evals return null). Do NOT pile the 60s-default
            // waitForSelector("body") on top of the exhausted poll — surface
            // the problem and move on so the caller can recover.
            logger.warn(
                "onDidNavigate: document never became ready after navigation (url='{}'). " +
                        "Navigation may have failed silently.",
                driver.currentUrl()
            )
        }
        delay(1000.milliseconds)
    }

    /**
     *
     * */
    private fun normalizeDomain(domain: String): String {
        val parts = domain.split(".")
        if (parts.isEmpty()) {
            return domain
        }

        val topDomain = domainAlias.getOrDefault(parts.first(), parts.first())

        return if (parts.size == 1) topDomain else listOf(topDomain).plus(parts.drop(1)).joinToString(".")
    }

    private fun normalizeArguments(
        domain: String,
        method: String,
        arguments: Map<String, Any?>,
        spec: ToolSpec?
    ): MutableMap<String, Any?> {
        if (arguments.isEmpty() || spec == null) {
            if (domain == "tab" && method in setOf("eval", "evaluateValue", "evaluateValueDetail")) {
                return normalizeTabEvaluationArguments(method, arguments)
            }
            return arguments.toMutableMap()
        }

        if (domain == "browser" && method in setOf("switchTab", "closeTab")) {
            return normalizeBrowserTabArguments(arguments, spec)
        }

        if (domain == "tab" && method in setOf("eval", "evaluateValue", "evaluateValueDetail")) {
            return normalizeTabEvaluationArguments(method, arguments)
        }

        val normalized = linkedMapOf<String, Any?>()

        arguments.entries
            .filter { it.key.toIntOrNull() == null }
            .forEach { (key, value) -> normalized[key] = value }

        arguments.entries
            .mapNotNull { entry -> entry.key.toIntOrNull()?.let { it to entry.value } }
            .sortedBy { it.first }
            .forEach { (index, value) ->
                val targetName = spec.arguments.getOrNull(index)?.name ?: index.toString()
                normalized.putIfAbsent(targetName, value)
            }

        return normalized.toMutableMap()
    }

    private fun normalizeBrowserTabArguments(arguments: Map<String, Any?>, spec: ToolSpec): MutableMap<String, Any?> {
        val normalized = linkedMapOf<String, Any?>()

        arguments.entries
            .filter { it.key.toIntOrNull() == null }
            .forEach { (key, value) -> normalized[key] = value }

        val positional = arguments.entries
            .mapNotNull { entry -> entry.key.toIntOrNull()?.let { it to entry.value } }
            .sortedBy { it.first }

        val shouldTreatSinglePositionalAsTabId = positional.size == 1 &&
            positional.first().first == 0 &&
            !normalized.containsKey("index") &&
            !normalized.containsKey("tabId")

        positional.forEach { (index, value) ->
            val targetName = when {
                shouldTreatSinglePositionalAsTabId && value !is Number -> "tabId"
                else -> spec.arguments.getOrNull(index)?.name ?: index.toString()
            }
            normalized.putIfAbsent(targetName, value)
        }

        return normalized.toMutableMap()
    }

    private fun normalizeTabEvaluationArguments(method: String, arguments: Map<String, Any?>): MutableMap<String, Any?> {
        val normalized = linkedMapOf<String, Any?>()

        arguments.entries
            .filter { it.key.toIntOrNull() == null }
            .forEach { (key, value) -> normalized[key] = value }

        val positional = arguments.entries
            .mapNotNull { entry -> entry.key.toIntOrNull()?.let { it to entry.value } }
            .sortedBy { it.first }

        positional.forEach { (index, value) ->
            val targetName = when (index) {
                0 -> "expression"
                1 -> if (method == "eval") "selector" else "functionDeclaration"
                else -> index.toString()
            }
            normalized.putIfAbsent(targetName, value)
        }

        return normalized.toMutableMap()
    }
}
