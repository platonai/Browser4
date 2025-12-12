package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.AgenticSession

/**
 * Metadata describing a Skill, including its identity, dependencies, and capabilities.
 *
 * @property name Unique identifier for the skill (e.g., "web-scraper", "data-extractor").
 * @property version Semantic version string (e.g., "1.0.0").
 * @property description Human-readable description of what the skill does.
 * @property author Optional author/organization information.
 * @property tags Categorization tags for discovery (e.g., ["web", "scraping", "extraction"]).
 * @property dependencies List of other skill names this skill depends on.
 * @property requiredTools List of tool domains/methods this skill requires (e.g., ["driver.click", "agent.extract"]).
 */
data class SkillMetadata(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String? = null,
    val tags: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val requiredTools: Set<String> = emptySet(),
)

/**
 * Runtime context provided to a Skill during execution.
 * Contains the session, input parameters, and any skill-specific state.
 *
 * @property session The agentic session providing browser automation and AI capabilities.
 * @property parameters Input parameters passed to the skill invocation.
 * @property state Mutable state that can be used by the skill across multiple invocations.
 */
data class SkillContext(
    val session: AgenticSession,
    val parameters: Map<String, Any>,
    val state: MutableMap<String, Any> = mutableMapOf(),
)

/**
 * Represents a modular, reusable task module (Skill) that can be dynamically loaded and executed.
 *
 * A Skill encapsulates:
 * - Instructions and templates for specific tasks
 * - Executable logic that uses browser automation and AI tools
 * - Metadata describing its purpose, dependencies, and capabilities
 *
 * Skills are automatically loaded when relevant tasks arise, providing consistent,
 * accurate automation for specialized use cases.
 *
 * Example implementations:
 * - Web scraping skill that navigates and extracts data
 * - Form filling skill that populates and submits forms
 * - Data analysis skill that processes extracted information
 *
 * Contract:
 * - Skills must be thread-safe and reentrant
 * - Skills should validate their context and parameters
 * - Skills should handle errors gracefully and return meaningful results
 */
interface Skill {
    /**
     * Metadata describing this skill's identity and capabilities.
     */
    val metadata: SkillMetadata

    /**
     * Execute the skill's core logic within the provided context.
     *
     * @param context Runtime context with session, parameters, and state.
     * @return Result of the skill execution.
     * @throws IllegalArgumentException if required parameters are missing or invalid.
     * @throws IllegalStateException if the skill cannot execute in the current state.
     */
    suspend fun execute(context: SkillContext): ActResult

    /**
     * Validate that all required parameters and dependencies are present.
     *
     * @param context The context to validate.
     * @return true if the context is valid for execution, false otherwise.
     */
    fun validate(context: SkillContext): Boolean = true

    /**
     * Optional initialization hook called when the skill is first loaded.
     * Can be used for one-time setup, resource allocation, or validation.
     */
    fun initialize() {}

    /**
     * Optional cleanup hook called when the skill is unloaded or the system shuts down.
     * Should release any resources held by the skill.
     */
    fun cleanup() {}
}

/**
 * Base abstract implementation of Skill that provides common functionality.
 * Custom skills can extend this class to reduce boilerplate.
 */
abstract class AbstractSkill(
    override val metadata: SkillMetadata
) : Skill {

    /**
     * Helper method to get a required parameter from context.
     *
     * @param context The skill context.
     * @param key The parameter key.
     * @return The parameter value.
     * @throws IllegalArgumentException if the parameter is missing.
     */
    protected fun getRequiredParameter(context: SkillContext, key: String): Any {
        return context.parameters[key]
            ?: throw IllegalArgumentException("Required parameter '$key' not found for skill '${metadata.name}'")
    }

    /**
     * Helper method to get an optional parameter from context.
     *
     * @param context The skill context.
     * @param key The parameter key.
     * @param default Default value if parameter is not present.
     * @return The parameter value or default.
     */
    protected fun <T> getOptionalParameter(context: SkillContext, key: String, default: T): T {
        @Suppress("UNCHECKED_CAST")
        return context.parameters[key] as? T ?: default
    }

    override fun validate(context: SkillContext): Boolean {
        // Validate required tools are available
        // This is a basic check; more sophisticated validation could query the session
        return true
    }
}
