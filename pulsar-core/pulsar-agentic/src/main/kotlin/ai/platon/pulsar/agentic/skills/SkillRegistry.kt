package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing the lifecycle of Skills.
 *
 * The SkillRegistry provides:
 * - Registration and discovery of skills
 * - Lifecycle management (initialization, cleanup)
 * - Query by name, tag, or dependency
 * - Thread-safe access for concurrent environments
 *
 * Skills are registered by name and can be retrieved for execution.
 * The registry maintains strong references to skills and calls their
 * lifecycle hooks appropriately.
 */
class SkillRegistry {
    private val logger = getLogger(SkillRegistry::class)

    private val skills = ConcurrentHashMap<String, Skill>()
    private val skillsByTag = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Register a skill in the registry.
     * If a skill with the same name already exists, it will be replaced after cleanup.
     *
     * @param skill The skill to register.
     * @return true if registration succeeded, false if initialization failed.
     */
    fun register(skill: Skill): Boolean {
        val name = skill.metadata.name
        
        // Check for existing skill and clean it up
        skills[name]?.let { existing ->
            logger.info("Replacing existing skill: $name")
            runCatching { existing.cleanup() }
                .onFailure { e -> logger.warn("Failed to cleanup existing skill $name", e) }
        }

        return runCatching {
            skill.initialize()
            skills[name] = skill
            
            // Index by tags
            skill.metadata.tags.forEach { tag ->
                skillsByTag.computeIfAbsent(tag) { ConcurrentHashMap.newKeySet() }.add(name)
            }
            
            logger.info("Registered skill: $name (version ${skill.metadata.version})")
            true
        }.getOrElse { e ->
            logger.error("Failed to register skill: $name", e)
            false
        }
    }

    /**
     * Unregister and cleanup a skill.
     *
     * @param name The skill name to unregister.
     * @return The unregistered skill, or null if not found.
     */
    fun unregister(name: String): Skill? {
        val skill = skills.remove(name) ?: return null
        
        runCatching {
            skill.cleanup()
            
            // Remove from tag indices
            skill.metadata.tags.forEach { tag ->
                skillsByTag[tag]?.remove(name)
            }
            
            logger.info("Unregistered skill: $name")
        }.onFailure { e ->
            logger.warn("Error during skill cleanup: $name", e)
        }
        
        return skill
    }

    /**
     * Get a skill by name.
     *
     * @param name The skill name.
     * @return The skill, or null if not found.
     */
    fun get(name: String): Skill? = skills[name]

    /**
     * Check if a skill is registered.
     *
     * @param name The skill name.
     * @return true if the skill exists in the registry.
     */
    fun contains(name: String): Boolean = skills.containsKey(name)

    /**
     * Get all registered skill names.
     *
     * @return Set of all skill names.
     */
    fun getSkillNames(): Set<String> = skills.keys.toSet()

    /**
     * Find skills by tag.
     *
     * @param tag The tag to search for.
     * @return List of skills matching the tag.
     */
    fun findByTag(tag: String): List<Skill> {
        val names = skillsByTag[tag] ?: return emptyList()
        return names.mapNotNull { skills[it] }
    }

    /**
     * Find skills whose descriptions or names contain the query string.
     *
     * @param query The search query (case-insensitive).
     * @return List of matching skills.
     */
    fun search(query: String): List<Skill> {
        val lowerQuery = query.lowercase()
        return skills.values.filter { skill ->
            skill.metadata.name.lowercase().contains(lowerQuery) ||
                skill.metadata.description.lowercase().contains(lowerQuery) ||
                skill.metadata.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Get all registered skills.
     *
     * @return List of all skills.
     */
    fun getAllSkills(): List<Skill> = skills.values.toList()

    /**
     * Get detailed information about a skill including its dependencies.
     *
     * @param name The skill name.
     * @return SkillInfo with metadata and dependency status, or null if not found.
     */
    fun getSkillInfo(name: String): SkillInfo? {
        val skill = skills[name] ?: return null
        val missingDependencies = skill.metadata.dependencies.filterNot { contains(it) }
        
        return SkillInfo(
            metadata = skill.metadata,
            isAvailable = missingDependencies.isEmpty(),
            missingDependencies = missingDependencies.toSet()
        )
    }

    /**
     * Clear all skills and cleanup their resources.
     */
    fun clear() {
        val skillNames = skills.keys.toList()
        skillNames.forEach { unregister(it) }
        skillsByTag.clear()
        logger.info("Cleared all skills from registry")
    }

    /**
     * Get the total count of registered skills.
     *
     * @return Number of registered skills.
     */
    fun size(): Int = skills.size
}

/**
 * Information about a skill including its availability status.
 *
 * @property metadata The skill's metadata.
 * @property isAvailable Whether all dependencies are satisfied.
 * @property missingDependencies Set of dependency skill names that are not registered.
 */
data class SkillInfo(
    val metadata: SkillMetadata,
    val isAvailable: Boolean,
    val missingDependencies: Set<String>
)
