package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.getLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Central manager for the Skills framework, coordinating registry, loader, and executor.
 *
 * The SkillManager provides a unified API for:
 * - Loading and registering skills
 * - Discovering available skills
 * - Executing skills within sessions
 * - Managing skill lifecycle
 *
 * This is the primary entry point for working with Skills in the system.
 */
class SkillManager(
    val registry: SkillRegistry = SkillRegistry(),
    val executor: SkillExecutor = SkillExecutor(registry),
    private val defaultTimeout: Duration = 5.minutes
) {
    private val logger = getLogger(SkillManager::class)
    
    private val loader = SkillLoader(registry)
    private var initialized = false

    /**
     * Initialize the skill manager by loading built-in skills.
     * This is called automatically on first use.
     *
     * @param loadBuiltIns Whether to load built-in skills (default: true).
     * @return Number of skills loaded.
     */
    @Synchronized
    fun initialize(loadBuiltIns: Boolean = true): Int {
        if (initialized) {
            logger.warn("SkillManager already initialized")
            return 0
        }

        val count = if (loadBuiltIns) {
            loader.loadBuiltInSkills()
        } else {
            0
        }

        initialized = true
        logger.info("SkillManager initialized with $count skills")
        return count
    }

    /**
     * Ensure the manager is initialized before operations.
     */
    private fun ensureInitialized() {
        if (!initialized) {
            initialize()
        }
    }

    /**
     * Execute a skill by name.
     *
     * @param skillName The name of the skill to execute.
     * @param session The agentic session.
     * @param parameters Input parameters for the skill.
     * @param timeout Optional timeout override.
     * @return Result of skill execution.
     */
    suspend fun executeSkill(
        skillName: String,
        session: AgenticSession,
        parameters: Map<String, Any> = emptyMap(),
        timeout: Duration = defaultTimeout
    ): ActResult {
        ensureInitialized()
        return executor.execute(skillName, session, parameters, timeout)
    }

    /**
     * Register a custom skill.
     *
     * @param skill The skill to register.
     * @return true if registration succeeded.
     */
    fun registerSkill(skill: Skill): Boolean {
        ensureInitialized()
        return registry.register(skill)
    }

    /**
     * Register multiple skills.
     *
     * @param skills List of skills to register.
     * @return Number of skills successfully registered.
     */
    fun registerSkills(skills: List<Skill>): Int {
        ensureInitialized()
        return skills.count { registerSkill(it) }
    }

    /**
     * Unregister a skill by name.
     *
     * @param skillName The skill name.
     * @return The unregistered skill, or null if not found.
     */
    fun unregisterSkill(skillName: String): Skill? {
        return registry.unregister(skillName)
    }

    /**
     * Get a skill by name.
     *
     * @param skillName The skill name.
     * @return The skill, or null if not found.
     */
    fun getSkill(skillName: String): Skill? {
        ensureInitialized()
        return registry.get(skillName)
    }

    /**
     * Check if a skill exists.
     *
     * @param skillName The skill name.
     * @return true if the skill is registered.
     */
    fun hasSkill(skillName: String): Boolean {
        ensureInitialized()
        return registry.contains(skillName)
    }

    /**
     * Get all registered skill names.
     *
     * @return Set of skill names.
     */
    fun getSkillNames(): Set<String> {
        ensureInitialized()
        return registry.getSkillNames()
    }

    /**
     * Find skills by tag.
     *
     * @param tag The tag to search for.
     * @return List of matching skills.
     */
    fun findSkillsByTag(tag: String): List<Skill> {
        ensureInitialized()
        return registry.findByTag(tag)
    }

    /**
     * Search for skills by query string.
     *
     * @param query Search query (case-insensitive).
     * @return List of matching skills.
     */
    fun searchSkills(query: String): List<Skill> {
        ensureInitialized()
        return registry.search(query)
    }

    /**
     * Get detailed information about a skill.
     *
     * @param skillName The skill name.
     * @return SkillInfo with metadata and status, or null if not found.
     */
    fun getSkillInfo(skillName: String): SkillInfo? {
        ensureInitialized()
        return registry.getSkillInfo(skillName)
    }

    /**
     * Get all registered skills.
     *
     * @return List of all skills.
     */
    fun getAllSkills(): List<Skill> {
        ensureInitialized()
        return registry.getAllSkills()
    }

    /**
     * Load skills from a directory.
     *
     * @param directoryPath Path to the directory containing skill classes.
     * @param basePackage Base package for classes in the directory.
     * @return Number of skills loaded.
     */
    fun loadSkillsFromDirectory(directoryPath: String, basePackage: String = ""): Int {
        ensureInitialized()
        return loader.loadFromDirectory(java.nio.file.Paths.get(directoryPath), basePackage)
    }

    /**
     * Load a skill class by fully qualified name.
     *
     * @param className Fully qualified class name.
     * @return true if the skill was loaded successfully.
     */
    fun loadSkillClass(className: String): Boolean {
        ensureInitialized()
        return loader.loadSkillClass(className)
    }

    /**
     * Check if a skill can be executed (exists and has all dependencies satisfied).
     *
     * @param skillName The skill name.
     * @return true if the skill can be executed.
     */
    fun canExecuteSkill(skillName: String): Boolean {
        ensureInitialized()
        return executor.canExecute(skillName)
    }

    /**
     * Get a summary of the skill manager's state.
     *
     * @return Map containing statistics about registered skills.
     */
    fun getStatistics(): Map<String, Any> {
        ensureInitialized()
        val skills = getAllSkills()
        val totalSkills = skills.size
        val availableSkills = skills.count { skill ->
            registry.getSkillInfo(skill.metadata.name)?.isAvailable ?: false
        }
        val allTags = skills.flatMap { it.metadata.tags }.toSet()

        return mapOf(
            "totalSkills" to totalSkills,
            "availableSkills" to availableSkills,
            "unavailableSkills" to (totalSkills - availableSkills),
            "uniqueTags" to allTags.size,
            "tags" to allTags
        )
    }

    /**
     * Shutdown the skill manager and cleanup all skills.
     */
    fun shutdown() {
        logger.info("Shutting down SkillManager")
        registry.clear()
        initialized = false
    }
}

/**
 * Global singleton instance of SkillManager for convenience.
 * Applications can use this or create their own instances.
 */
object Skills {
    private val globalManager by lazy { SkillManager() }

    /**
     * Get the global SkillManager instance.
     */
    fun getManager(): SkillManager = globalManager

    /**
     * Execute a skill using the global manager.
     */
    suspend fun execute(
        skillName: String,
        session: AgenticSession,
        parameters: Map<String, Any> = emptyMap()
    ): ActResult = globalManager.executeSkill(skillName, session, parameters)

    /**
     * Register a skill using the global manager.
     */
    fun register(skill: Skill): Boolean = globalManager.registerSkill(skill)

    /**
     * Get all skill names using the global manager.
     */
    fun getSkillNames(): Set<String> = globalManager.getSkillNames()
}
