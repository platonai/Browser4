package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import java.nio.file.Path

/**
 * A minimal [Skill] implementation created directly from a [SkillDefinition].
 *
 * This is the glue between the on-disk/on-classpath skill protocol (SKILL.md + optional folders)
 * and the in-memory [SkillRegistry].
 *
 * Design goals:
 * - Self-contained: no hard dependency on Kotlin example skill classes.
 * - Loadable: registers metadata/dependencies so composition and discovery work.
 * - Safe-by-default execution: returns a metadata-only result unless a concrete executor is added.
 */
class DefinitionBackedSkill(
    val definition: SkillDefinition,
    private val origin: Origin
) : AbstractSkill() {

    private val logger = getLogger(this)

    sealed class Origin {
        data class FileSystem(val directory: Path) : Origin()
        data class Classpath(val resourceBase: String) : Origin()

        override fun toString(): String = when (this) {
            is FileSystem -> "filesystem:$directory"
            is Classpath -> "classpath:$resourceBase"
        }
    }

    override val metadata: SkillMetadata = SkillMetadata(
        id = definition.skillId,
        name = definition.metadata["displayName"] ?: definition.name,
        version = definition.version,
        description = definition.description,
        author = definition.author,
        dependencies = definition.dependencies,
        tags = definition.tags
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        // MVP: protocol compliance + discovery. Execution is intentionally non-operative
        // until a safe, sandboxed executor is introduced (kts/python/etc.).
        logger.debug("Executing definition-backed skill '{}' from {} with params keys: {}",
            metadata.id, origin, params.keys)

        val payload = mapOf(
            "skillId" to definition.skillId,
            "name" to definition.name,
            "version" to definition.version,
            "origin" to origin.toString(),
            "scriptsPath" to definition.scriptsPath?.toString(),
            "referencesPath" to definition.referencesPath?.toString(),
            "assetsPath" to definition.assetsPath?.toString(),
            "parameters" to definition.parameters.keys.toList(),
            "examples" to definition.examples
        )

        return SkillResult.success(
            data = payload,
            message = "Skill '${definition.skillId}' is loaded from ${origin} (execution not implemented)"
        )
    }
}
