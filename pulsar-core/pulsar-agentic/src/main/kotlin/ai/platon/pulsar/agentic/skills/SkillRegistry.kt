package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for Claude Skills.
 *
 * This registry manages skill definitions and provides methods for
 * skill discovery, registration, and export.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class SkillRegistry private constructor() {
    private val logger = getLogger(this)

    /** Skills indexed by name */
    private val skills = ConcurrentHashMap<String, ClaudeSkill>()

    companion object {
        /** Singleton instance */
        val instance: SkillRegistry by lazy { SkillRegistry() }
    }

    init {
        // Register predefined skills on initialization
        registerPredefinedSkills()
    }

    /**
     * Register predefined skills.
     */
    private fun registerPredefinedSkills() {
        try {
            val predefinedSkills = SkillGenerator.getAllPredefinedSkills()
            predefinedSkills.forEach { skill ->
                skills[skill.name] = skill
            }
            logger.info("✓ Registered {} predefined skills", predefinedSkills.size)
        } catch (e: Exception) {
            logger.warn("Failed to register predefined skills: {}", e.message)
        }
    }

    // ========================================================================
    // Skill Management
    // ========================================================================

    /**
     * Register a skill.
     *
     * @param skill The skill to register
     * @throws IllegalArgumentException if a skill with the same name exists
     */
    fun registerSkill(skill: ClaudeSkill) {
        require(skill.name.isNotBlank()) { "Skill name must not be blank" }

        if (skills.containsKey(skill.name)) {
            throw IllegalArgumentException(
                "Skill '${skill.name}' is already registered. " +
                "Use unregisterSkill() first if you want to replace it."
            )
        }

        skills[skill.name] = skill
        logger.info("✓ Registered skill: {} ({} tools)", skill.name, skill.toolCount)
    }

    /**
     * Unregister a skill.
     *
     * @param name The skill name to unregister
     * @return true if removed, false if not found
     */
    fun unregisterSkill(name: String): Boolean {
        val removed = skills.remove(name)
        if (removed != null) {
            logger.info("✓ Unregistered skill: {}", name)
            return true
        }
        return false
    }

    /**
     * Get a skill by name.
     */
    fun getSkill(name: String): ClaudeSkill? = skills[name]

    /**
     * Get all registered skills.
     */
    fun getAllSkills(): List<ClaudeSkill> = skills.values.toList()

    /**
     * Get skills by category.
     */
    fun getSkillsByCategory(category: SkillCategory): List<ClaudeSkill> {
        return skills.values.filter { it.category == category }
    }

    /**
     * Check if a skill exists.
     */
    fun hasSkill(name: String): Boolean = skills.containsKey(name)

    /**
     * Get skill names.
     */
    fun getSkillNames(): List<String> = skills.keys.toList()

    // ========================================================================
    // Search and Discovery
    // ========================================================================

    /**
     * Search skills by keyword.
     */
    fun searchSkills(keyword: String): List<ClaudeSkill> {
        val lowercaseKeyword = keyword.lowercase()
        return skills.values.filter { skill ->
            skill.name.lowercase().contains(lowercaseKeyword) ||
            skill.displayName.lowercase().contains(lowercaseKeyword) ||
            skill.description.lowercase().contains(lowercaseKeyword) ||
            skill.metadata?.tags?.any { it.lowercase().contains(lowercaseKeyword) } == true
        }
    }

    /**
     * Find skills that contain a specific tool.
     */
    fun findSkillsWithTool(toolName: String): List<ClaudeSkill> {
        return skills.values.filter { skill ->
            skill.tools.any { it.name == toolName }
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Clear all registered skills.
     */
    fun clear() {
        skills.clear()
        logger.info("✓ Cleared all skills")
    }

    /**
     * Reset to initial state (only predefined skills).
     */
    fun reset() {
        clear()
        registerPredefinedSkills()
        logger.info("✓ Reset skill registry to initial state")
    }

    /**
     * Get statistics about the registry.
     */
    fun getStats(): Map<String, Any> {
        val categoryStats = skills.values
            .groupBy { it.category }
            .mapValues { it.value.size }

        val totalTools = skills.values.sumOf { it.toolCount }

        return mapOf(
            "skills" to skills.size,
            "totalTools" to totalTools,
            "byCategory" to categoryStats.mapKeys { it.key.name },
        )
    }
}
