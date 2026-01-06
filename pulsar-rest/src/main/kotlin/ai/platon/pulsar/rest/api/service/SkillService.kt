package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.skills.*
import org.springframework.stereotype.Service

/**
 * Service layer for Claude Skills operations.
 *
 * Provides business logic for skill management, generation, and export.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@Service
class SkillService {

    private val registry = SkillRegistry.instance

    // ========================================================================
    // Skill Listing
    // ========================================================================

    /**
     * Get all registered skills.
     */
    fun listSkills(): List<ClaudeSkill> {
        return registry.getAllSkills()
    }

    /**
     * Get skill by name.
     */
    fun getSkill(name: String): ClaudeSkill? {
        return registry.getSkill(name)
    }

    /**
     * Get skills by category.
     */
    fun getSkillsByCategory(category: SkillCategory): List<ClaudeSkill> {
        return registry.getSkillsByCategory(category)
    }

    /**
     * Search skills by keyword.
     */
    fun searchSkills(keyword: String): List<ClaudeSkill> {
        return registry.searchSkills(keyword)
    }

    /**
     * Find skills containing a specific tool.
     */
    fun findSkillsWithTool(toolName: String): List<ClaudeSkill> {
        return registry.findSkillsWithTool(toolName)
    }

    /**
     * Get all skill names.
     */
    fun getSkillNames(): List<String> {
        return registry.getSkillNames()
    }

    // ========================================================================
    // Skill Export
    // ========================================================================

    /**
     * Export skill to JSON format.
     */
    fun exportSkillJson(name: String): String? {
        val skill = registry.getSkill(name) ?: return null
        return SkillRenderer.renderSkillJson(skill)
    }

    /**
     * Export skill to YAML format.
     */
    fun exportSkillYaml(name: String): String? {
        val skill = registry.getSkill(name) ?: return null
        return SkillRenderer.renderSkillYaml(skill)
    }

    /**
     * Export skill to Claude Desktop format.
     */
    fun exportSkillClaudeDesktop(name: String): String? {
        val skill = registry.getSkill(name) ?: return null
        return SkillRenderer.renderClaudeDesktopSkill(skill)
    }

    /**
     * Export skill tools in MCP format.
     */
    fun exportSkillToolsMcp(name: String): String? {
        val skill = registry.getSkill(name) ?: return null
        return SkillRenderer.renderSkillToolsMcp(skill)
    }

    /**
     * Export skill documentation in Markdown format.
     */
    fun exportSkillMarkdown(name: String): String? {
        val skill = registry.getSkill(name) ?: return null
        return SkillRenderer.renderSkillMarkdown(skill)
    }

    /**
     * Export all skills to Claude Desktop config format.
     */
    fun exportAllSkillsClaudeDesktop(): String {
        val skills = registry.getAllSkills()
        return SkillRenderer.renderClaudeDesktopConfig(skills)
    }

    /**
     * Export all skills to JSON array.
     */
    fun exportAllSkillsJson(): String {
        val skills = registry.getAllSkills()
        return SkillRenderer.renderSkillsJson(skills)
    }

    // ========================================================================
    // Skill Management
    // ========================================================================

    /**
     * Register a custom skill.
     */
    fun registerSkill(skill: ClaudeSkill): Boolean {
        return try {
            registry.registerSkill(skill)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Unregister a skill.
     */
    fun unregisterSkill(name: String): Boolean {
        return registry.unregisterSkill(name)
    }

    /**
     * Check if skill exists.
     */
    fun hasSkill(name: String): Boolean {
        return registry.hasSkill(name)
    }

    // ========================================================================
    // Skill Generation
    // ========================================================================

    /**
     * Generate a custom skill from tool names.
     */
    fun generateCustomSkill(
        name: String,
        displayName: String,
        description: String,
        instructions: String,
        toolNames: List<String>,
        category: SkillCategory = SkillCategory.CUSTOM
    ): ClaudeSkill {
        return SkillGenerator.generateSkillFromToolNames(
            name = name,
            displayName = displayName,
            description = description,
            instructions = instructions,
            toolNames = toolNames,
            category = category,
        )
    }

    /**
     * Get all predefined skills.
     */
    fun getPredefinedSkills(): List<ClaudeSkill> {
        return SkillGenerator.getAllPredefinedSkills()
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    /**
     * Get registry statistics.
     */
    fun getStats(): Map<String, Any> {
        return registry.getStats()
    }

    /**
     * Get available categories.
     */
    fun getCategories(): List<Map<String, String>> {
        return SkillCategory.entries.map { category ->
            mapOf(
                "name" to category.name,
                "displayName" to category.displayName,
            )
        }
    }
}
