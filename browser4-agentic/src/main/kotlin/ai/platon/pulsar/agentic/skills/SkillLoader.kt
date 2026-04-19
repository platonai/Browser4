package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.async

/**
 * Loader for dynamically loading and managing skills.
 *
 * The SkillLoader provides functionality for:
 * - Loading skills from various sources (class instances, reflection, etc.)
 * - Batch loading multiple skills
 * - Dependency resolution
 * - Error handling during loading
 *
 * ## Example Usage:
 * ```kotlin
 * val loader = SkillLoader(SkillRegistry.instance)
 * val context = SkillContext(sessionId = "session-123")
 *
 * // Load a single skill
 * loader.load(WebScrapingSkill(), context)
 *
 * // Load multiple skills with dependency resolution
 * loader.loadAll(listOf(skill1, skill2, skill3), context)
 * ```
 */
class SkillLoader(
    private val registry: SkillRegistry = SkillRegistry.instance
) {
    private val logger = getLogger(this)

    /**
     * Load a single skill into the registry.
     *
     * @param skill The skill to load
     * @param context Execution context
     * @return true if loaded successfully, false otherwise
     */
    suspend fun load(skill: Skill, context: SkillContext): Boolean {
        return try {
            registry.register(skill, context)
            true
        } catch (e: Exception) {
            logger.warn("Failed to load skill '{}': {}", skill.metadata.id, e.message)
            false
        }
    }

    /**
     * Load multiple skills with automatic dependency resolution.
     *
     * Skills are loaded in dependency order to ensure all dependencies
     * are satisfied before loading a skill.
     *
     * @param skills List of skills to load
     * @param context Execution context
     * @return Map of skill ID to loading result (true if successful, false otherwise)
     */
    suspend fun loadAll(skills: List<Skill>, context: SkillContext): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        val toLoad = skills.toMutableList()
        val loaded = mutableSetOf<String>()

        // Keep trying to load skills until all are loaded or no progress is made
        var madeProgress = true
        while (toLoad.isNotEmpty() && madeProgress) {
            madeProgress = false

            val iterator = toLoad.iterator()
            while (iterator.hasNext()) {
                val skill = iterator.next()
                val skillId = skill.metadata.id

                // Check if all dependencies are loaded
                val depsLoaded = skill.metadata.dependencies.all { it in loaded }

                if (depsLoaded) {
                    val success = load(skill, context)
                    results[skillId] = success
                    if (success) {
                        loaded.add(skillId)
                        madeProgress = true
                    }
                    iterator.remove()
                }
            }
        }

        // Any remaining skills have unsatisfied dependencies
        for (skill in toLoad) {
            results[skill.metadata.id] = false
            logger.warn(
                "Cannot load skill '{}': unsatisfied dependencies: {}",
                skill.metadata.id,
                skill.metadata.dependencies.filterNot { it in loaded }
            )
        }

        return results
    }

    /**
     * Reload a skill by unloading and loading it again.
     *
     * @param skill The skill to reload
     * @param context Execution context
     * @return true if reloaded successfully, false otherwise
     */
    suspend fun reload(skill: Skill, context: SkillContext): Boolean {
        val skillId = skill.metadata.id
        if (registry.contains(skillId)) {
            registry.unregister(skillId, context)
        }
        return load(skill, context)
    }

    /**
     * Unload a skill by ID.
     *
     * @param skillId The ID of the skill to unload
     * @param context Execution context
     * @return true if unloaded successfully, false otherwise
     */
    suspend fun unload(skillId: String, context: SkillContext): Boolean {
        return try {
            registry.unregister(skillId, context)
        } catch (e: Exception) {
            logger.warn("Failed to unload skill '{}': {}", skillId, e.message)
            false
        }
    }

    /**
     * Unload multiple skills.
     *
     * @param skillIds List of skill IDs to unload
     * @param context Execution context
     * @return Map of skill ID to unloading result
     */
    suspend fun unloadAll(skillIds: List<String>, context: SkillContext): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (skillId in skillIds) {
            results[skillId] = unload(skillId, context)
        }
        return results
    }
}

/**
 * Composer for creating composite skills from multiple skills.
 *
 * The SkillComposer allows creating complex skills by combining
 * simpler skills in a pipeline or parallel execution pattern.
 *
 * ## Example Usage:
 * ```kotlin
 * val composer = SkillComposer(SkillRegistry.instance)
 *
 * // Create a composite skill that runs skills in sequence
 * val composite = composer.sequential(
 *     "data-collection",
 *     listOf("web-scraping", "data-cleaning", "data-export")
 * )
 * ```
 */
class SkillComposer(
    private val registry: SkillRegistry = SkillRegistry.instance
) {
    private val logger = getLogger(this)

    /**
     * Create a composite skill that executes skills sequentially.
     *
     * @param compositeId ID for the composite skill
     * @param skillIds List of skill IDs to execute in order
     * @param metadata Optional metadata for the composite skill
     * @return A composite skill
     */
    fun sequential(
        compositeId: String,
        skillIds: List<String>,
        metadata: SkillMetadata? = null
    ): Skill {
        return SequentialCompositeSkill(
            compositeId = compositeId,
            skillIds = skillIds,
            registry = registry,
            customMetadata = metadata
        )
    }

    /**
     * Create a composite skill that executes skills in parallel.
     *
     * @param compositeId ID for the composite skill
     * @param skillIds List of skill IDs to execute in parallel
     * @param metadata Optional metadata for the composite skill
     * @return A composite skill
     */
    fun parallel(
        compositeId: String,
        skillIds: List<String>,
        metadata: SkillMetadata? = null
    ): Skill {
        return ParallelCompositeSkill(
            compositeId = compositeId,
            skillIds = skillIds,
            registry = registry,
            customMetadata = metadata
        )
    }
}

/**
 * Composite skill that executes multiple skills sequentially.
 */
private class SequentialCompositeSkill(
    compositeId: String,
    private val skillIds: List<String>,
    private val registry: SkillRegistry,
    customMetadata: SkillMetadata?
) : AbstractSkill() {
    private val logger = getLogger(this)

    override val metadata: SkillMetadata = customMetadata ?: SkillMetadata(
        id = compositeId,
        name = "Sequential Composite: ${skillIds.joinToString(", ")}",
        description = "Executes skills sequentially: ${skillIds.joinToString(" -> ")}",
        dependencies = skillIds
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val results = mutableListOf<SkillResult>()

        for (skillId in skillIds) {
            val result = try {
                registry.execute(skillId, context, params)
            } catch (e: Exception) {
                logger.warn("Error executing skill '{}' in composite: {}", skillId, e.message)
                return SkillResult.failure(
                    message = "Sequential composite failed at skill '$skillId': ${e.message}",
                    metadata = mapOf("failedSkillId" to skillId, "results" to results)
                )
            }

            results.add(result)

            // Stop if a skill fails
            if (!result.success) {
                return SkillResult.failure(
                    message = "Sequential composite failed at skill '$skillId': ${result.message}",
                    metadata = mapOf("failedSkillId" to skillId, "results" to results)
                )
            }
        }

        return SkillResult.success(
            data = results,
            message = "All ${skillIds.size} skills executed successfully"
        )
    }
}

/**
 * Composite skill that executes multiple skills in parallel.
 */
private class ParallelCompositeSkill(
    compositeId: String,
    private val skillIds: List<String>,
    private val registry: SkillRegistry,
    customMetadata: SkillMetadata?
) : AbstractSkill() {
    private val logger = getLogger(this)

    override val metadata: SkillMetadata = customMetadata ?: SkillMetadata(
        id = compositeId,
        name = "Parallel Composite: ${skillIds.joinToString(", ")}",
        description = "Executes skills in parallel: ${skillIds.joinToString(" | ")}",
        dependencies = skillIds
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val results = mutableMapOf<String, SkillResult>()
        val failures = mutableListOf<String>()

        // Execute all skills in parallel
        kotlinx.coroutines.coroutineScope {
            skillIds.map { skillId ->
                async {
                    try {
                        val result = registry.execute(skillId, context, params)
                        results[skillId] = result
                        if (!result.success) {
                            failures.add(skillId)
                        }
                    } catch (e: Exception) {
                        logger.warn("Error executing skill '{}' in parallel composite: {}", skillId, e.message)
                        results[skillId] = SkillResult.failure("Execution failed: ${e.message}")
                        failures.add(skillId)
                    }
                }
            }.forEach { it.await() }
        }

        return if (failures.isEmpty()) {
            SkillResult.success(
                data = results,
                message = "All ${skillIds.size} skills executed successfully"
            )
        } else {
            SkillResult.failure(
                message = "Parallel composite had ${failures.size} failures: ${failures.joinToString()}",
                metadata = mapOf("results" to results, "failures" to failures)
            )
        }
    }
}
