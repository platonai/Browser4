package ai.platon.pulsar.agentic.skills.execution

import ai.platon.pulsar.agentic.skills.SkillDefinition
import java.nio.file.Path

/**
 * Utilities for executing and reading resources from resource-backed skills.
 */
object SkillExecutionSupport {

    /**
     * Resolve a relative path inside a skill root directory.
     *
     * @throws IllegalArgumentException if the path is blank, absolute, or escapes skill root.
     */
    fun resolveInSkillRoot(skillRoot: Path, relativePath: String): Path {
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }

        val normalizedRel = relativePath.replace('\\', '/')
        require(!normalizedRel.startsWith("/")) { "relativePath must be relative: $relativePath" }
        require(!normalizedRel.contains("://")) { "relativePath must be a file path, not URL: $relativePath" }

        // Prevent traversal even if it would normalize back into the root.
        require(normalizedRel.split('/').none { it == ".." }) { "relativePath must not contain '..': $relativePath" }

        val resolved = skillRoot.resolve(normalizedRel).normalize()
        require(resolved.startsWith(skillRoot.normalize())) {
            "relativePath must not escape skill root: $relativePath"
        }
        return resolved
    }

    /**
     * Compute the skill root directory from a [SkillDefinition].
     */
    fun skillRoot(definition: SkillDefinition): Path {
        return definition.scriptsPath?.parent
            ?: definition.referencesPath?.parent
            ?: definition.assetsPath?.parent
            ?: throw IllegalArgumentException("Skill root directory is not available for ${definition.skillId}")
    }
}
