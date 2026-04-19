package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.model.ToolSpec
import kotlin.reflect.KClass

/**
 * Metadata describing a skill module.
 *
 * @property id Unique identifier for the skill (e.g., "web-scraping", "form-filling")
 * @property name Human-readable name of the skill
 * @property version Semantic version of the skill (e.g., "1.0.0")
 * @property description Brief description of what the skill does
 * @property author Author or organization that created the skill
 * @property dependencies List of skill IDs that this skill depends on
 * @property tags Categorization tags for skill discovery
 */
data class SkillMetadata(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val dependencies: List<String> = emptyList(),
    val tags: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "Skill id must not be blank" }
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(version.matches(Regex("""\d+\.\d+\.\d+"""))) {
            "Skill version must follow semantic versioning (e.g., 1.0.0)"
        }
    }
}

/**
 * Context provided to skills during execution.
 *
 * The SkillContext provides access to:
 * - Session information
 * - Shared resources
 * - Configuration parameters
 * - Inter-skill communication
 *
 * @property sessionId Unique identifier for the current session
 * @property config Configuration parameters for the skill
 * @property sharedResources Map of shared resources available to all skills
 */
data class SkillContext(
    val sessionId: String,
    val config: Map<String, Any> = emptyMap(),
    val sharedResources: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * Get a configuration value.
     *
     * @param key Configuration key
     * @param default Default value if key is not found
     * @return Configuration value or default
     */
    fun <T> getConfig(key: String, default: T): T {
        @Suppress("UNCHECKED_CAST")
        return config[key] as? T ?: default
    }

    /**
     * Get a shared resource.
     *
     * @param key Resource key
     * @return Resource value or null if not found
     */
    fun <T> getResource(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return sharedResources[key] as? T
    }

    /**
     * Set a shared resource.
     *
     * @param key Resource key
     * @param value Resource value
     */
    fun setResource(key: String, value: Any) {
        sharedResources[key] = value
    }
}

/**
 * Result of a skill execution.
 *
 * @property success Whether the skill execution was successful
 * @property data Result data from the skill execution
 * @property message Optional message describing the result
 * @property metadata Additional metadata about the execution
 */
data class SkillResult(
    val success: Boolean,
    val data: Any? = null,
    val message: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(data: Any? = null, message: String? = null): SkillResult {
            return SkillResult(success = true, data = data, message = message)
        }

        fun failure(message: String, metadata: Map<String, Any> = emptyMap()): SkillResult {
            return SkillResult(success = false, message = message, metadata = metadata)
        }
    }
}

/**
 * Base interface for all skills.
 *
 * A Skill is a self-contained module that encapsulates reusable browser automation patterns.
 * Skills can be dynamically loaded, composed, and have lifecycle hooks for initialization
 * and cleanup.
 *
 * ## Lifecycle:
 * 1. **Load**: `onLoad()` is called when the skill is registered
 * 2. **Execution**: `execute()` is called to perform the skill's task
 *    - `onBeforeExecute()` is called before execution
 *    - `onAfterExecute()` is called after execution
 * 3. **Unload**: `onUnload()` is called when the skill is unregistered
 *
 * ## Example:
 * ```kotlin
 * class WebScrapingSkill : Skill {
 *     override val metadata = SkillMetadata(
 *         id = "web-scraping",
 *         name = "Web Scraping",
 *         description = "Extract data from web pages"
 *     )
 *
 *     override suspend fun execute(
 *         context: SkillContext,
 *         params: Map<String, Any>
 *     ): SkillResult {
 *         // Implementation
 *         return SkillResult.success(data = extractedData)
 *     }
 * }
 * ```
 */
interface Skill {
    /**
     * Metadata describing this skill.
     */
    val metadata: SkillMetadata

    /**
     * Tool call specifications provided by this skill.
     * These are exposed to the agent for use in automation tasks.
     */
    val toolSpec: List<ToolSpec>
        get() = emptyList()

    /**
     * The target class type for tool execution.
     * This is used when the skill provides custom tool executors.
     */
    val targetClass: KClass<*>?
        get() = null

    /**
     * Execute the skill with the given parameters.
     *
     * @param context Execution context providing session info and shared resources
     * @param params Parameters specific to this skill execution
     * @return Result of the skill execution
     */
    suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult

    /**
     * Called when the skill is loaded into the registry.
     * Use this for initialization, resource allocation, or validation.
     *
     * @param context Execution context
     */
    suspend fun onLoad(context: SkillContext) {
        // Default implementation does nothing
    }

    /**
     * Called when the skill is unloaded from the registry.
     * Use this for cleanup, resource deallocation, or state persistence.
     *
     * @param context Execution context
     */
    suspend fun onUnload(context: SkillContext) {
        // Default implementation does nothing
    }

    /**
     * Called before executing the skill.
     * Use this for pre-execution validation or setup.
     *
     * @param context Execution context
     * @param params Execution parameters
     * @return true to proceed with execution, false to skip
     */
    suspend fun onBeforeExecute(context: SkillContext, params: Map<String, Any>): Boolean {
        // Default implementation allows execution
        return true
    }

    /**
     * Called after executing the skill.
     * Use this for post-execution cleanup or logging.
     *
     * @param context Execution context
     * @param params Execution parameters
     * @param result Execution result
     */
    suspend fun onAfterExecute(context: SkillContext, params: Map<String, Any>, result: SkillResult) {
        // Default implementation does nothing
    }

    /**
     * Validate the skill's dependencies and configuration.
     *
     * @param context Execution context
     * @return true if validation passes, false otherwise
     */
    suspend fun validate(context: SkillContext): Boolean {
        // Default implementation always passes
        return true
    }
}

/**
 * Abstract base class for skills providing common functionality.
 */
abstract class AbstractSkill : Skill {
    private var isLoaded = false

    override suspend fun onLoad(context: SkillContext) {
        if (isLoaded) {
            throw IllegalStateException("Skill ${metadata.id} is already loaded")
        }
        isLoaded = true
    }

    override suspend fun onUnload(context: SkillContext) {
        if (!isLoaded) {
            throw IllegalStateException("Skill ${metadata.id} is not loaded")
        }
        isLoaded = false
    }

    /**
     * Check if this skill is currently loaded.
     */
    fun isLoaded(): Boolean = isLoaded
}
