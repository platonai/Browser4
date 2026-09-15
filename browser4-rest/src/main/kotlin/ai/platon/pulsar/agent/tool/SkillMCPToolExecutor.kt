package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolResultSchemas
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [SkillService] management operations as MCP tools.
 *
 * Domain: `skill_mgmt`
 *
 * This is distinct from [ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor] (domain: `skill`)
 * which handles skill *execution*. This executor handles skill *management*:
 * listing, inspecting, installing, uninstalling, and reloading skills.
 *
 * Supported methods:
 * - `list()` — List all registered skills
 * - `info(id)` — Get detailed information about a skill
 * - `install(path, overwrite?)` — Install a skill from a server-side directory path
 * - `uninstall(id)` — Uninstall a skill by ID
 * - `reload(id)` — Reload a skill from its source directory
 */
class SkillMCPToolExecutor(
    private val skillService: SkillService,
) : AbstractToolExecutor() {

    override val domain: String = "skill"
    override val receiverClass: KClass<*> = SkillService::class

    init {
        toolSpec["list"] = ToolSpec(
            domain = domain,
            method = "list",
            arguments = emptyList(),
            returnType = "List<SkillSummary>",
            outputSchema = ToolResultSchemas.SKILL_LIST,
            description = "List all registered skills with lightweight summaries.",
            examples = listOf(
                ToolExample(title = "List the installed skills", args = emptyMap(), runnable = true),
            ),
        )

        toolSpec["info"] = ToolSpec(
            domain = domain,
            method = "info",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Skill id as reported by `skill.list`. Required."),
            ),
            returnType = "SkillDetail",
            outputSchema = ToolResultSchemas.SKILL_INFO,
            description = "Get detailed information about a skill by ID.",
            examples = listOf(
                ToolExample(title = "Inspect a bundled skill", args = mapOf("id" to "browser4-cli")),
            ),
        )

        toolSpec["install"] = ToolSpec(
            domain = domain,
            method = "install",
            arguments = listOf(
                ToolSpec.Arg(
                    "path", "String", null,
                    "Server-side directory containing SKILL.md. Required; the path resolves on the server.",
                ),
                ToolSpec.Arg("overwrite", "Boolean", "false", "Replace an existing skill with the same id."),
            ),
            returnType = "InstallResult",
            description = "Install a skill from a server-side directory path containing SKILL.md.",
            examples = listOf(
                ToolExample(
                    title = "Install a skill from a server directory",
                    args = mapOf("path" to "/opt/skills/my-skill"),
                ),
            ),
        )

        toolSpec["uninstall"] = ToolSpec(
            domain = domain,
            method = "uninstall",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Skill id to remove. Required."),
            ),
            returnType = "InstallResult",
            description = "Uninstall a skill by ID.",
            examples = listOf(
                ToolExample(title = "Remove a skill", args = mapOf("id" to "my-skill")),
            ),
        )

        toolSpec["reload"] = ToolSpec(
            domain = domain,
            method = "reload",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Skill id to reload from its source directory. Required."),
            ),
            returnType = "Map",
            description = "Reload a skill from its source directory.",
            examples = listOf(
                ToolExample(title = "Reload after editing SKILL.md", args = mapOf("id" to "browser4-cli")),
            ),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "list" -> skillService.listSkills()
            "info" -> {
                val id = paramString(args, "id", functionName)!!
                skillService.getSkill(id)
            }
            "install" -> {
                val sourcePath = paramString(args, "path", functionName)!!
                val overwrite = paramBool(args, "overwrite", functionName, required = false, default = false) ?: false
                val sourceDir = Path.of(sourcePath)
                if (!Files.isDirectory(sourceDir)) {
                    throw IllegalArgumentException("Source path is not a directory: $sourcePath")
                }
                skillService.installSkill(sourceDir, overwrite)
            }
            "uninstall" -> {
                val id = paramString(args, "id", functionName)!!
                skillService.uninstallSkill(id)
            }
            "reload" -> {
                val id = paramString(args, "id", functionName)!!
                val reloaded = skillService.reloadSkill(id)
                mapOf(
                    "success" to reloaded,
                    "skillId" to id,
                    "message" to if (reloaded) "Skill '$id' reloaded successfully" else "Failed to reload skill '$id'"
                )
            }
            else -> throw IllegalArgumentException("Unsupported skill_mgmt method: $functionName")
        }
    }
}
