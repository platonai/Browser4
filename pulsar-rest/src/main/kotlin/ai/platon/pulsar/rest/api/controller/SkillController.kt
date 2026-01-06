package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.rest.api.service.SkillService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API Controller for Claude Skills operations.
 *
 * Provides endpoints for:
 * - Skill listing and retrieval
 * - Skill export in various formats (JSON, YAML, Claude Desktop)
 * - Custom skill generation
 * - Skill management
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@RestController
@CrossOrigin
@RequestMapping("api/skills")
class SkillController(
    private val skillService: SkillService
) {
    // ========================================================================
    // Skill Listing Endpoints
    // ========================================================================

    /**
     * List all available skills.
     *
     * @return List of skill summaries
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listSkills(): SkillListResponse {
        val skills = skillService.listSkills()
        return SkillListResponse(
            skills = skills.map { it.toSummary() }
        )
    }

    /**
     * Get a specific skill by name.
     *
     * @param name Skill name
     * @return Full skill definition or 404
     */
    @GetMapping("/{name}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSkill(@PathVariable name: String): ResponseEntity<ClaudeSkill> {
        val skill = skillService.getSkill(name)
        return if (skill != null) {
            ResponseEntity.ok(skill)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get skills by category.
     *
     * @param category Category name
     * @return List of skills in the category
     */
    @GetMapping("/category/{category}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getSkillsByCategory(@PathVariable category: String): SkillListResponse {
        val skillCategory = try {
            SkillCategory.valueOf(category.uppercase())
        } catch (e: IllegalArgumentException) {
            return SkillListResponse(skills = emptyList())
        }
        val skills = skillService.getSkillsByCategory(skillCategory)
        return SkillListResponse(skills = skills.map { it.toSummary() })
    }

    /**
     * Search skills by keyword.
     *
     * @param q Search query
     * @return Matching skills
     */
    @GetMapping("/search", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun searchSkills(@RequestParam q: String): SkillListResponse {
        val skills = skillService.searchSkills(q)
        return SkillListResponse(skills = skills.map { it.toSummary() })
    }

    /**
     * Find skills containing a specific tool.
     *
     * @param tool Tool name
     * @return Skills containing the tool
     */
    @GetMapping("/with-tool", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun findSkillsWithTool(@RequestParam tool: String): SkillListResponse {
        val skills = skillService.findSkillsWithTool(tool)
        return SkillListResponse(skills = skills.map { it.toSummary() })
    }

    // ========================================================================
    // Skill Export Endpoints
    // ========================================================================

    /**
     * Export skill to JSON format.
     *
     * @param name Skill name
     * @return JSON string
     */
    @GetMapping("/{name}/export/json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportSkillJson(@PathVariable name: String): ResponseEntity<String> {
        val json = skillService.exportSkillJson(name)
        return if (json != null) {
            ResponseEntity.ok(json)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Export skill to YAML format.
     *
     * @param name Skill name
     * @return YAML string
     */
    @GetMapping("/{name}/export/yaml", produces = ["text/yaml"])
    fun exportSkillYaml(@PathVariable name: String): ResponseEntity<String> {
        val yaml = skillService.exportSkillYaml(name)
        return if (yaml != null) {
            ResponseEntity.ok(yaml)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Export skill to Claude Desktop format.
     *
     * @param name Skill name
     * @return Claude Desktop skill JSON
     */
    @GetMapping("/{name}/export/claude-desktop", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportSkillClaudeDesktop(@PathVariable name: String): ResponseEntity<String> {
        val json = skillService.exportSkillClaudeDesktop(name)
        return if (json != null) {
            ResponseEntity.ok(json)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Export skill tools in MCP format.
     *
     * @param name Skill name
     * @return MCP tools JSON
     */
    @GetMapping("/{name}/export/mcp", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportSkillToolsMcp(@PathVariable name: String): ResponseEntity<String> {
        val json = skillService.exportSkillToolsMcp(name)
        return if (json != null) {
            ResponseEntity.ok(json)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Export skill documentation in Markdown format.
     *
     * @param name Skill name
     * @return Markdown documentation
     */
    @GetMapping("/{name}/export/markdown", produces = ["text/markdown"])
    fun exportSkillMarkdown(@PathVariable name: String): ResponseEntity<String> {
        val markdown = skillService.exportSkillMarkdown(name)
        return if (markdown != null) {
            ResponseEntity.ok(markdown)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Export all skills to Claude Desktop config format.
     *
     * @return skills.json content
     */
    @GetMapping("/export/claude-desktop", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportAllSkillsClaudeDesktop(): String {
        return skillService.exportAllSkillsClaudeDesktop()
    }

    /**
     * Export all skills to JSON array.
     *
     * @return JSON array of all skills
     */
    @GetMapping("/export/json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportAllSkillsJson(): String {
        return skillService.exportAllSkillsJson()
    }

    // ========================================================================
    // Skill Management Endpoints
    // ========================================================================

    /**
     * Create a custom skill.
     *
     * @param request Custom skill creation request
     * @return Created skill or error
     */
    @PostMapping(
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun createCustomSkill(@RequestBody request: CreateSkillRequest): ResponseEntity<SkillOperationResponse> {
        // Generate the skill
        val skill = skillService.generateCustomSkill(
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            instructions = request.instructions,
            toolNames = request.toolNames,
            category = request.category?.let { 
                try { 
                    SkillCategory.valueOf(it.uppercase()) 
                } catch (e: IllegalArgumentException) { 
                    SkillCategory.CUSTOM 
                }
            } ?: SkillCategory.CUSTOM,
        )

        // Register if requested
        if (request.register == true) {
            val registered = skillService.registerSkill(skill)
            if (!registered) {
                return ResponseEntity.badRequest().body(
                    SkillOperationResponse(
                        success = false,
                        message = "Skill '${request.name}' already exists",
                    )
                )
            }
        }

        return ResponseEntity.ok(
            SkillOperationResponse(
                success = true,
                message = "Skill created successfully",
                skill = skill,
            )
        )
    }

    /**
     * Delete a custom skill.
     *
     * @param name Skill name
     * @return Operation result
     */
    @DeleteMapping("/{name}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteSkill(@PathVariable name: String): SkillOperationResponse {
        val removed = skillService.unregisterSkill(name)
        return SkillOperationResponse(
            success = removed,
            message = if (removed) "Skill removed" else "Skill not found",
        )
    }

    // ========================================================================
    // Metadata Endpoints
    // ========================================================================

    /**
     * Get available skill categories.
     *
     * @return List of categories
     */
    @GetMapping("/categories", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getCategories(): List<Map<String, String>> {
        return skillService.getCategories()
    }

    /**
     * Get skill templates (predefined skills).
     *
     * @return List of predefined skill summaries
     */
    @GetMapping("/templates", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTemplates(): SkillListResponse {
        val skills = skillService.getPredefinedSkills()
        return SkillListResponse(skills = skills.map { it.toSummary() })
    }

    /**
     * Get registry statistics.
     *
     * @return Statistics map
     */
    @GetMapping("/stats", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getStats(): Map<String, Any> {
        return skillService.getStats()
    }
}

// ============================================================================
// Request/Response DTOs
// ============================================================================

/**
 * Skill summary for list responses.
 */
data class SkillSummary(
    val name: String,
    val displayName: String,
    val description: String,
    val category: String,
    val toolCount: Int,
    val version: String?,
)

/**
 * Extension function to convert ClaudeSkill to summary.
 */
fun ClaudeSkill.toSummary() = SkillSummary(
    name = name,
    displayName = displayName,
    description = description,
    category = category.displayName,
    toolCount = toolCount,
    version = metadata?.version,
)

/**
 * Response for skill list endpoints.
 */
data class SkillListResponse(
    val skills: List<SkillSummary>
)

/**
 * Request for creating a custom skill.
 */
data class CreateSkillRequest(
    val name: String,
    val displayName: String,
    val description: String,
    val instructions: String,
    val toolNames: List<String>,
    val category: String? = null,
    val register: Boolean? = false,
)

/**
 * Response for skill operations.
 */
data class SkillOperationResponse(
    val success: Boolean,
    val message: String,
    val skill: ClaudeSkill? = null,
)
