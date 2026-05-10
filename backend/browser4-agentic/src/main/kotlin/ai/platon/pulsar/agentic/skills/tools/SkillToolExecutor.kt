package ai.platon.pulsar.agentic.skills.tools

import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.skills.Skill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillInstaller
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.nio.file.Paths
import kotlin.reflect.KClass

/**
 * Expose registered [Skill]s as agent tools.
 *
 * Domain is fixed to [domain]. Each skill is invoked via method name == skillId.
 *
 * Example tool call (after wiring the target):
 * `skill.run(id: String, params: Map<String, Any?> = emptyMap())`
 */
class SkillToolExecutor(
    private val registry: SkillRegistry = SkillRegistry.instance,
) : AbstractToolExecutor(), ToolCallSpecificationProvider {
    private val scriptTools = SkillScriptToolExecutor(registry)

    override val domain: String = "skill"

    override val receiverClass: KClass<*> = SkillToolTarget::class

    init {
        // 1) Discovery surface
        toolSpec["list"] = ToolSpec(
            domain = domain,
            method = "list",
            arguments = listOf(
                ToolSpec.Arg("maxDescriptionChars", "Int", "512")
            ),
            returnType = "List<SkillSummary>",
            description = "List registered skills (summary only, for discovery/matching)"
        )

        // 2) Activation surface
        toolSpec["activate"] = ToolSpec(
            domain = domain,
            method = "activate",
            arguments = listOf(
                ToolSpec.Arg("id", "String")
            ),
            returnType = "SkillActivation",
            description = "Load full SKILL.md (and resource pointers) for a skill (activation)"
        )

        // 3) Stable execution entry point
        toolSpec["run"] = ToolSpec(
            domain = domain,
            method = "run",
            arguments = listOf(
                ToolSpec.Arg("id", "String"),
                ToolSpec.Arg("params", "Map<String, Any?>", "emptyMap()")
            ),
            returnType = "SkillResult",
            description = "Run a registered skill by id"
        )

        // 4) Document-driven installation
        toolSpec["install"] = ToolSpec(
            domain = domain,
            method = "install",
            arguments = listOf(
                ToolSpec.Arg("sourceDir", "String"),
                ToolSpec.Arg("overwrite", "Boolean", "false")
            ),
            returnType = "InstallResult",
            description = "Install a skill from a source directory containing SKILL.md. " +
                "Reads the documentation, deploys skill files, and registers the skill."
        )

        // 5) Uninstall a skill
        toolSpec["uninstall"] = ToolSpec(
            domain = domain,
            method = "uninstall",
            arguments = listOf(
                ToolSpec.Arg("id", "String")
            ),
            returnType = "InstallResult",
            description = "Uninstall a skill by removing its files and unregistering it"
        )

        // 6) Read skill documentation
        toolSpec["readDocumentation"] = ToolSpec(
            domain = domain,
            method = "readDocumentation",
            arguments = listOf(
                ToolSpec.Arg("id", "String")
            ),
            returnType = "String",
            description = "Read the full SKILL.md documentation for a registered skill"
        )

        scriptTools.getToolSpecs().forEach { (method, spec) ->
            toolSpec.putIfAbsent(method, spec)
        }
    }

    override fun getToolCallSpecifications(): List<ToolSpec> {
        // Expose: (1) stable skill.* entry points; (2) optional richer specs provided by skills.
        // Skills may use domains like "skill.debug.scraping"; those should be registered separately if desired.
        return buildList {
            addAll(toolSpec.values)
            registry.getAll().flatMapTo(this) { it.toolSpec }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(receiver is SkillToolTarget) { "Target must be a SkillToolTarget" }

        // Get agentId from SkillToolTarget context if available
        val agentId = receiver.context.sessionId

        return when (functionName) {
            "list" -> {
                val maxChars = (args["maxDescriptionChars"] as? Number)?.toInt() ?: 512
                val skills = registry.listSkillSummaries(maxDescriptionChars = maxChars)

                onSkillsListed(agentId, skills.size, maxChars)

                skills
            }

            "activate" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val activation = registry.activateSkill(id)

                onSkillActivated(agentId, id)

                activation
            }

            "run" -> {
                validateArgs(args, allowed = setOf("id", "params"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val params = args["params"]

                val paramsMap: Map<String, Any?> = when (params) {
                    null -> emptyMap()
                    is Map<*, *> -> params.entries.associate { (k, v) -> k.toString() to v }
                    is String -> {
                        val trimmed = params.trim()
                        if (trimmed.isEmpty()) emptyMap()
                        else {
                            @Suppress("UNCHECKED_CAST")
                            pulsarObjectMapper().readValue(trimmed, Map::class.java) as Map<String, Any?>
                        }
                    }
                    else -> throw IllegalArgumentException("params must be Map<String, Any?> or JSON string for $functionName | actual='${params::class.qualifiedName}'")
                }

                onWillRunSkill(agentId, id, paramsMap)

                val startTime = System.currentTimeMillis()

                try {
                    val result = receiver.execute(id, paramsMap)
                    val duration = System.currentTimeMillis() - startTime

                    onDidRunSkill(agentId, id, duration, result)

                    result
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime

                    onSkillError(agentId, id, duration, e)

                    throw e
                }
            }

            "install" -> {
                validateArgs(args, allowed = setOf("sourceDir", "overwrite"), required = setOf("sourceDir"), functionName)
                val sourceDir = paramString(args, "sourceDir", functionName)!!
                val overwrite = (args["overwrite"] as? Boolean) ?: false

                val installer = SkillInstaller(registry)
                val result = installer.install(
                    sourceDir = Paths.get(sourceDir),
                    context = receiver.context,
                    overwrite = overwrite
                )

                onSkillInstalled(agentId, result.skillId, result.success, result.message)

                result
            }

            "uninstall" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!

                val installer = SkillInstaller(registry)
                val result = installer.uninstall(id, receiver.context)

                onSkillUninstalled(agentId, id, result.success, result.message)

                result
            }

            "readDocumentation" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!

                val installer = SkillInstaller(registry)
                val doc = installer.readDocumentation(id)
                    ?: throw IllegalArgumentException("No documentation found for skill '$id'")

                doc
            }

            "readReference",
            "runScript" -> scriptTools.callFunctionOn(domain, functionName, args, receiver)

            else -> throw IllegalArgumentException("Unsupported $domain method: $functionName(${args.keys})")
        }
    }

    // ------------------------------ Event Handler Methods --------------------------------

    private fun onSkillsListed(agentId: String, count: Int, maxDescriptionChars: Int) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_SKILLS_LISTED,
            agentId = agentId,
            message = "Listed $count skills",
            metadata = mapOf(
                "count" to count,
                "maxDescriptionChars" to maxDescriptionChars
            )
        )
    }

    private fun onSkillActivated(agentId: String, skillId: String) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_SKILL_ACTIVATED,
            agentId = agentId,
            message = "Skill activated: $skillId",
            metadata = mapOf("skillId" to skillId)
        )
    }

    private fun onWillRunSkill(agentId: String, skillId: String, paramsMap: Map<String, Any?>) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_WILL_RUN_SKILL,
            agentId = agentId,
            message = "Running skill: $skillId",
            metadata = mapOf(
                "skillId" to skillId,
                "paramsKeys" to paramsMap.keys.toList()
            )
        )
    }

    private fun onDidRunSkill(agentId: String, skillId: String, duration: Long, result: Any?) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_DID_RUN_SKILL,
            agentId = agentId,
            message = "Skill completed: $skillId",
            metadata = mapOf(
                "skillId" to skillId,
                "duration" to duration,
                "success" to (result != null)
            )
        )
    }

    private fun onSkillError(agentId: String, skillId: String, duration: Long, e: Exception) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_SKILL_ERROR,
            agentId = agentId,
            message = "Skill failed: $skillId - ${e.message}",
            metadata = mapOf(
                "skillId" to skillId,
                "duration" to duration,
                "error" to e.message
            )
        )
    }

    private fun onSkillInstalled(agentId: String, skillId: String, success: Boolean, message: String) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_SKILL_INSTALLED,
            agentId = agentId,
            message = "Skill installed: $skillId (success=$success)",
            metadata = mapOf(
                "skillId" to skillId,
                "success" to success,
                "message" to message
            )
        )
    }

    private fun onSkillUninstalled(agentId: String, skillId: String, success: Boolean, message: String) {
        AgentEventBus.emitSkillEvent(
            eventType = AgenticEvents.Skill.ON_SKILL_UNINSTALLED,
            agentId = agentId,
            message = "Skill uninstalled: $skillId (success=$success)",
            metadata = mapOf(
                "skillId" to skillId,
                "success" to success,
                "message" to message
            )
        )
    }
}
