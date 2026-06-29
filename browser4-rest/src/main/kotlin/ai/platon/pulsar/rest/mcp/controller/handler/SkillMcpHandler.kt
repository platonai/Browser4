package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPContent
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.errorResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.textResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP handler for skill management tools.
 *
 * Provides MCP tool endpoints for listing, inspecting, installing, uninstalling,
 * and reloading skills.
 */
class SkillMcpHandler(
    private val skillService: SkillService,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(SkillMcpHandler::class.java)

    companion object {
        val TOOL_NAMES = listOf(
            "skill_list",
            "skill_info",
            "skill_install",
            "skill_uninstall",
            "skill_reload",
        )
    }

    /**
     * List all registered skills.
     */
    fun handleSkillList(): ResponseEntity<MCPToolCallResponse> {
        return try {
            val summaries = skillService.listSkills()
            val json = objectMapper.writeValueAsString(summaries)
            logger.info("Listed {} skill(s)", summaries.size)
            ResponseEntity.ok(textResponse(json))
        } catch (e: Exception) {
            logger.error("Failed to list skills: {}", e.message, e)
            ResponseEntity.ok(errorResponse("Failed to list skills: ${e.message}"))
        }
    }

    /**
     * Get detailed information about a single skill.
     */
    fun handleSkillInfo(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        return try {
            val skillId = request.arguments?.get("id")?.toString()
                ?: return ResponseEntity.ok(errorResponse("Missing required parameter: id"))

            val detail = skillService.getSkill(skillId)
                ?: return ResponseEntity.ok(errorResponse("Skill not found: $skillId"))

            val json = objectMapper.writeValueAsString(detail)
            logger.info("Retrieved skill info: {}", skillId)
            ResponseEntity.ok(textResponse(json))
        } catch (e: Exception) {
            logger.error("Failed to get skill info: {}", e.message, e)
            ResponseEntity.ok(errorResponse("Failed to get skill info: ${e.message}"))
        }
    }

    /**
     * Install a skill from a server-side directory path.
     *
     * Required parameter: `path` — path to a skill directory containing SKILL.md
     * Optional parameter: `overwrite` — if "true", overwrite existing skill (default: false)
     */
    suspend fun handleSkillInstall(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        return try {
            val args = request.arguments ?: emptyMap()
            val sourcePath = args["path"]?.toString()
                ?: return ResponseEntity.ok(errorResponse("Missing required parameter: path"))

            val overwrite = args["overwrite"]?.toString()?.toBooleanStrictOrNull() ?: false

            val sourceDir = Path.of(sourcePath)
            if (!Files.isDirectory(sourceDir)) {
                return ResponseEntity.ok(errorResponse("Source path is not a directory: $sourcePath"))
            }

            val result = skillService.installSkill(sourceDir, overwrite)
            val json = objectMapper.writeValueAsString(result)
            logger.info("Skill install result: {} — {}", result.skillId, result.message)
            ResponseEntity.ok(textResponse(json))
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid skill install: {}", e.message)
            ResponseEntity.ok(errorResponse("Invalid install request: ${e.message}"))
        } catch (e: IllegalStateException) {
            logger.warn("Skill install conflict: {}", e.message)
            ResponseEntity.ok(errorResponse("Install conflict: ${e.message}"))
        } catch (e: Exception) {
            logger.error("Failed to install skill: {}", e.message, e)
            ResponseEntity.ok(errorResponse("Failed to install skill: ${e.message}"))
        }
    }

    /**
     * Uninstall a skill by ID.
     *
     * Required parameter: `id` — the skill identifier
     */
    suspend fun handleSkillUninstall(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        return try {
            val skillId = request.arguments?.get("id")?.toString()
                ?: return ResponseEntity.ok(errorResponse("Missing required parameter: id"))

            val result = skillService.uninstallSkill(skillId)
            val json = objectMapper.writeValueAsString(result)
            logger.info("Skill uninstall result: {} — {}", skillId, result.message)
            ResponseEntity.ok(textResponse(json))
        } catch (e: Exception) {
            logger.error("Failed to uninstall skill: {}", e.message, e)
            ResponseEntity.ok(errorResponse("Failed to uninstall skill: ${e.message}"))
        }
    }

    /**
     * Reload a skill from its source directory.
     *
     * Required parameter: `id` — the skill identifier
     */
    suspend fun handleSkillReload(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        return try {
            val skillId = request.arguments?.get("id")?.toString()
                ?: return ResponseEntity.ok(errorResponse("Missing required parameter: id"))

            val reloaded = skillService.reloadSkill(skillId)
            val response = mapOf(
                "success" to reloaded,
                "skillId" to skillId,
                "message" to if (reloaded) "Skill '$skillId' reloaded successfully" else "Failed to reload skill '$skillId'"
            )
            val json = objectMapper.writeValueAsString(response)
            logger.info("Skill reload result: {} — {}", skillId, reloaded)
            ResponseEntity.ok(textResponse(json))
        } catch (e: IllegalArgumentException) {
            logger.warn("Skill not found for reload: {}", request.arguments?.get("id"))
            ResponseEntity.ok(errorResponse("Skill not found: ${e.message}"))
        } catch (e: Exception) {
            logger.error("Failed to reload skill: {}", e.message, e)
            ResponseEntity.ok(errorResponse("Failed to reload skill: ${e.message}"))
        }
    }
}
