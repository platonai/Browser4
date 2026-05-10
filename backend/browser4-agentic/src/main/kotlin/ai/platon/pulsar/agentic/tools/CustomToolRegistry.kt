package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for custom tool executors that can be added at runtime.
 *
 * This registry allows users to extend the agent's capabilities by registering
 * custom tool executors for new domains beyond the built-in ones (driver, browser, fs, agent, system).
 *
 * ## Example Usage:
 *
 * ```kotlin
 * // Create a custom tool executor
 * class DatabaseToolExecutor : AbstractToolExecutor() {
 *     override val domain = "db"
 *     override val targetClass = Database::class
 *
 *     override suspend fun execute(
 *         objectName: String,
 *         functionName: String,
 *         args: Map<String, Any?>,
 *         target: Any
 *     ): Any? {
 *         val db = target as Database
 *         return when (functionName) {
 *             "query" -> db.query(paramString(args, "sql", functionName)!!)
 *             else -> throw IllegalArgumentException("Unsupported method: $functionName")
 *         }
 *     }
 * }
 *
 * // Register the custom tool
 * val registry = CustomToolRegistry.instance
 * registry.register(DatabaseToolExecutor())
 *
 * // Now the agent can use db.query() commands
 * ```
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class CustomToolRegistry private constructor() {
    private val logger = getLogger(this)

    private val executors = ConcurrentHashMap<String, ToolExecutor>()
    private val toolSpec = ConcurrentHashMap<String, MutableList<ToolSpec>>()

    companion object {
        /**
         * Singleton instance of the registry.
         */
        val instance: CustomToolRegistry by lazy { CustomToolRegistry() }
    }

    /**
     * Register a custom tool executor for a specific domain.
     *
     * If the executor also implements [ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider], its tool-call specs will be
     * available for prompt injection.
     *
     * @param executor The tool executor to register.
     * @throws IllegalArgumentException if an executor for this domain is already registered.
     */
    fun register(executor: ToolExecutor) {
        val domain = executor.domain
        require(domain.isNotBlank()) { "Tool executor domain must not be blank" }

        if (executors.containsKey(domain)) {
            throw IllegalArgumentException(
                "Tool executor for domain '$domain' is already registered. " +
                    "Use unregister() first if you want to replace it."
            )
        }

        executors[domain] = executor

        val specs = (executor as? ToolCallSpecificationProvider)?.getToolCallSpecifications().orEmpty()
        if (specs.isNotEmpty()) {
            toolSpec.computeIfAbsent(domain) { mutableListOf() }.addAll(specs)
        }

        logger.info("✓ Registered custom tool executor for domain: {}", domain)
    }

    /**
     * Register a custom tool executor together with its prompt-visible tool-call specs.
     *
     * This is useful when the executor cannot (or doesn't want to) implement [ToolCallSpecificationProvider].
     */
    fun register(executor: ToolExecutor, specs: List<ToolSpec>) {
        register(executor)
        if (specs.isNotEmpty()) {
            toolSpec.computeIfAbsent(executor.domain) { mutableListOf() }.addAll(specs)
        }
    }

    /**
     * Unregister a custom tool executor for a specific domain.
     *
     * @param domain The domain to unregister.
     * @return true if an executor was unregistered, false if no executor was found for this domain.
     */
    fun unregister(domain: String): Boolean {
        val removed = executors.remove(domain)
        toolSpec.remove(domain)
        if (removed != null) {
            logger.info("✓ Unregistered custom tool executor for domain: {}", domain)
            return true
        }
        return false
    }

    /**
     * Get a custom tool executor for a specific domain.
     *
     * @param domain The domain to look up.
     * @return The tool executor if found, null otherwise.
     */
    fun get(domain: String): ToolExecutor? {
        return executors[domain]
    }

    /**
     * Check if a custom tool executor is registered for a specific domain.
     *
     * @param domain The domain to check.
     * @return true if an executor is registered for this domain, false otherwise.
     */
    fun contains(domain: String): Boolean {
        return executors.containsKey(domain)
    }

    /**
     * Get all registered custom tool executors.
     *
     * @return A list of all registered tool executors.
     */
    fun getAllExecutors(): List<ToolExecutor> {
        return executors.values.toList()
    }

    /**
     * Get all registered domains.
     *
     * @return A list of all registered domain names.
     */
    fun getAllDomains(): List<String> {
        return executors.keys.toList()
    }

    /**
     * Get prompt-visible tool-call specs for a given domain.
     */
    fun getToolCallSpecifications(domain: String): List<ToolSpec> {
        return toolSpec[domain]?.toList().orEmpty()
    }

    /**
     * Get all prompt-visible tool-call specs.
     */
    fun getAllToolCallSpecifications(): List<ToolSpec> {
        return toolSpec.values.flatten()
    }

    /**
     * Clear all registered custom tool executors.
     */
    fun clear() {
        executors.clear()
        toolSpec.clear()
        logger.info("✓ Cleared all custom tool executors")
    }

    /**
     * Get the count of registered custom tool executors.
     *
     * @return The number of registered executors.
     */
    fun size(): Int {
        return executors.size
    }
}
