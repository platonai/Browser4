package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.common.AgentShell
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import ai.platon.pulsar.agentic.tools.builtin.*
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import kotlinx.coroutines.delay
import java.nio.file.Path

class AgentToolExecutor constructor(
    val baseDir: Path,
    val agent: BasicBrowserAgent,
) {
    private val logger = getLogger(AgentToolExecutor::class)

    /**
     * Custom tool targets registry, mapping domain names to their corresponding target objects.
     * Users can register custom targets here for their custom tool executors.
     */
    private val _customTargets = mutableMapOf<String, Any>()

    val session: AgenticSession get() = agent.session
    val driver: WebDriver get() = session.getOrCreateBoundDriver()
    val fs: AgentFileSystem = AgentFileSystem(baseDir)
    val shell: AgentShell = AgentShell(baseDir)
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
    )

    val concreteExecutors: List<ToolExecutor> by lazy {
        listOf(
            BrowserTabToolExecutor(),
            BrowserToolExecutor(),
            FileSystemToolExecutor(),
            ShellToolExecutor(),
            AgentToolExecutor(),
            CommandToolExecutor(),
            system,
            skills
        )
    }

    val executor by lazy { BasicToolCallExecutor(concreteExecutors) }

    val customTargets: Map<String, Any> get() = _customTargets

    /**
     * Register a custom target object for a specific domain.
     * The target will be used when executing tool calls for the given domain.
     *
     * @param domain The domain name for the custom tool.
     * @param target The target object to be used by the custom tool executor.
     */
    fun registerCustomTarget(domain: String, target: Any) {
        _customTargets[domain] = target
        logger.info("✓ Registered custom target for domain: {}", domain)
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
            logger.info("✓ Unregistered custom target for domain: {}", domain)
            return true
        }
        return false
    }

    fun help(domain: String, method: String): String {
        // Check built-in executors first
        val builtInHelp = concreteExecutors.firstOrNull { it.domain == domain }?.help(method)
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
        val normalizedArguments = normalizeArguments(tc.arguments, spec)

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
        return concreteExecutors.associate { executor -> executor.domain to executor.getToolSpecs() }
    }

    /**
     * Returns the tool specification for a specific domain and method, or null if not found.
     *
     * @param domain The tool domain (e.g. "tab", "fs").
     * @param method The method name within the domain.
     * @return The [ToolSpec] for the given domain and method, or null.
     */
    fun getToolSpec(domain: String, method: String): ToolSpec? {
        return concreteExecutors.find { it.domain == domain }?.getToolSpecs()?.get(method)
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
            "browser" -> executor.callFunctionOn(normalized, driver.browser)
            "fs" -> executor.callFunctionOn(normalized, fs)
            "shell" -> executor.callFunctionOn(normalized, shell)
            "agent" -> executor.callFunctionOn(normalized, agent)
            "command" -> {
                // TODO: we need a real commandTarget in module pulsar-agentic, it is registered in pulsar-rest module currently
                val commandTarget = _customTargets["command"]
                    ?: throw UnsupportedOperationException(
                        "Command domain '${normalized.domain}' requires a registered CommandService target."
                    )
                executor.callFunctionOn(normalized, commandTarget)
            }
            "system" -> executor.callFunctionOn(normalized, system)
            "skill" -> executor.callFunctionOn(normalized, skillTarget)
            else -> {
                val customExecutor = CustomToolRegistry.instance.get(normalized.domain)
                if (customExecutor != null) {
                    val target = _customTargets[normalized.domain]
                        ?: throw UnsupportedOperationException(
                            "Custom domain '${normalized.domain}' is registered but no target object is available.")
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
            "switchTab" -> onDidSwitchTab(evaluate)
            "navigate" -> onDidNavigate(driver, tc, evaluate)
        }

        return tcResult
    }

    /**
     * Handle switching to a new tab by binding the target driver to the session.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onDidSwitchTab(evaluate: TcEvaluate) {
        val frontDriver = session.boundBrowser?.frontDriver
        if (frontDriver == null) {
            logger.warn("⚠️ No driver is in front after switchTab")
            return
        }

        val oldBoundDriver = session.boundDriver
        if (frontDriver == oldBoundDriver) {
            logger.warn("⚠️ The bound driver does not change after switchTab")
        }

        // bind the driver which has been brought to front just now
        session.bindDriver(frontDriver)
    }

    /**
     * TODO: add an option to driver.navigate() to wait
     * */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun onDidNavigate(driver: WebDriver, toolCall: ToolCall, evaluate: TcEvaluate) {
        driver.waitForNavigation()
        driver.waitForSelector("body")
        delay(3000)
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

    private fun normalizeArguments(arguments: Map<String, Any?>, spec: ToolSpec?): MutableMap<String, Any?> {
        if (arguments.isEmpty() || spec == null) {
            return arguments.toMutableMap()
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
}
