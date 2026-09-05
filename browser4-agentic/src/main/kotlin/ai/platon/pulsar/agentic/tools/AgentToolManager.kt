package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.common.AgentShell
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
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
    val workspaceRoot: Path = CodingWorkspace.workspaceRoot,
) {
    private val logger = getLogger(AgentToolManager::class)

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
    val codingShell: CodingAgentShell = CodingAgentShell(
        baseDir = workspaceRoot,
        // Independent/multi-tenant deployments can tighten defaults via system property:
        // -Dbrowser4.agent.allowDestructive=false denies rm/del/mv/cp/kill etc.
        allowDestructive = CodingWorkspace.allowDestructive,
    )
    /** Enhanced coding file system for full filesystem access */
    val codingFs: CodingAgentFileSystem = CodingAgentFileSystem(
        workspaceRoot = workspaceRoot,
        // Same tightening switch; note delete() additionally hard-protects the
        // workspace root and VCS directories regardless of this flag.
        allowExternalAccess = CodingWorkspace.allowExternalAccess,
        allowDestructive = CodingWorkspace.allowDestructive,
    )
    /** Composite target for the coding domain */
    val codingTarget: CodingToolExecutor.Target by lazy {
        CodingToolExecutor.Target(codingShell, codingFs)
    }
    /** CLI tool executor for browser4-cli integration (domain `b4`) */
    val b4CliExecutor: B4CliToolExecutor = B4CliToolExecutor(
        // M0: force CLI subprocesses onto THIS backend (BROWSER4_CLI_SERVER) so
        // the CLI can never auto-start/restart a server. Set by the hosting app
        // via `browser4.server.url` (system property or configuration).
        backendBaseUrl = session.sessionConfig.get("browser4.server.url"),
        defaultWorkingDir = workspaceRoot,
    )

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
    var toolExecutionRecorder: ((domain: String, method: String) -> Unit)? = null

    /** Notify the recorder (if wired) about one executed tool call. */
    fun notifyToolExecuted(domain: String, method: String) {
        toolExecutionRecorder?.invoke(domain, method)
    }

    /** Cancel all tracked CLI background jobs (agent close). */
    fun closeCliJobs() {
        b4CliExecutor.closeJobs()
    }

    init {
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
                executor.callFunctionOn(normalized, commandTarget)
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
                            ?: throw UnsupportedOperationException(
                                "Custom domain '${normalized.domain}' is registered but no target object is available."
                            )
                    }
                    customExecutor.callFunctionOn(normalized, target)
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
            index != null -> browser.listDrivers().filterIsInstance<WebDriver>().getOrNull(index)
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
