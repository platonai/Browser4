package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Executes Skills within an AgenticSession context.
 *
 * The SkillExecutor:
 * - Validates skill dependencies before execution
 * - Creates execution contexts with parameters
 * - Handles timeouts and error recovery
 * - Logs execution metrics and results
 *
 * Thread-safe for concurrent skill execution.
 */
class SkillExecutor(
    private val registry: SkillRegistry,
    private val defaultTimeout: Duration = 5.minutes
) {
    private val logger = getLogger(SkillExecutor::class)

    /**
     * Execute a skill by name.
     *
     * @param skillName The name of the skill to execute.
     * @param session The agentic session providing browser automation.
     * @param parameters Input parameters for the skill.
     * @param timeout Optional timeout override.
     * @return Result of skill execution.
     * @throws SkillNotFoundException if the skill is not registered.
     * @throws SkillExecutionException if execution fails.
     */
    suspend fun execute(
        skillName: String,
        session: AgenticSession,
        parameters: Map<String, Any> = emptyMap(),
        timeout: Duration = defaultTimeout
    ): ActResult {
        val skill = registry.get(skillName)
            ?: throw SkillNotFoundException("Skill not found: $skillName")

        return execute(skill, session, parameters, timeout)
    }

    /**
     * Execute a skill instance.
     *
     * @param skill The skill to execute.
     * @param session The agentic session providing browser automation.
     * @param parameters Input parameters for the skill.
     * @param timeout Execution timeout.
     * @return Result of skill execution.
     * @throws SkillExecutionException if execution fails.
     */
    suspend fun execute(
        skill: Skill,
        session: AgenticSession,
        parameters: Map<String, Any> = emptyMap(),
        timeout: Duration = defaultTimeout
    ): ActResult {
        val context = SkillContext(session, parameters)
        
        // Validate the skill can execute in this context
        if (!skill.validate(context)) {
            val message = "Skill validation failed: ${skill.metadata.name}"
            logger.warn(message)
            return ActResult(
                success = false,
                message = message,
                action = skill.metadata.name
            )
        }

        // Check dependencies
        val missingDeps = checkDependencies(skill)
        if (missingDeps.isNotEmpty()) {
            val message = "Missing skill dependencies: ${missingDeps.joinToString(", ")}"
            logger.warn(message)
            return ActResult(
                success = false,
                message = message,
                action = skill.metadata.name
            )
        }

        // Execute with timeout
        return try {
            logger.info("Executing skill: ${skill.metadata.name}")
            val startTime = System.currentTimeMillis()
            
            val result = withTimeout(timeout) {
                skill.execute(context)
            }
            
            val elapsedMs = System.currentTimeMillis() - startTime
            logger.info("Skill ${skill.metadata.name} completed in ${elapsedMs}ms")
            
            result
        } catch (e: Exception) {
            val message = "Skill execution failed: ${e.message}"
            logger.error(message, e)
            throw SkillExecutionException(skill.metadata.name, message, e)
        }
    }

    /**
     * Check if all dependencies for a skill are satisfied.
     *
     * @param skill The skill to check.
     * @return Set of missing dependency names.
     */
    private fun checkDependencies(skill: Skill): Set<String> {
        return skill.metadata.dependencies.filterNot { registry.contains(it) }.toSet()
    }

    /**
     * Execute multiple skills in sequence.
     *
     * @param skillNames List of skill names to execute.
     * @param session The agentic session.
     * @param parameters Shared parameters for all skills.
     * @return List of results for each skill.
     */
    suspend fun executeSequence(
        skillNames: List<String>,
        session: AgenticSession,
        parameters: Map<String, Any> = emptyMap()
    ): List<ActResult> {
        val results = mutableListOf<ActResult>()
        
        for (skillName in skillNames) {
            val result = try {
                execute(skillName, session, parameters)
            } catch (e: Exception) {
                logger.error("Failed to execute skill in sequence: $skillName", e)
                ActResult(
                    success = false,
                    message = "Skill execution failed: ${e.message}",
                    action = skillName
                )
            }
            
            results.add(result)
            
            // Stop sequence if a skill fails
            if (!result.success) {
                logger.warn("Stopping skill sequence due to failure: $skillName")
                break
            }
        }
        
        return results
    }

    /**
     * Check if a skill can be executed (exists and has all dependencies).
     *
     * @param skillName The skill name.
     * @return true if the skill can be executed.
     */
    fun canExecute(skillName: String): Boolean {
        val skill = registry.get(skillName) ?: return false
        return checkDependencies(skill).isEmpty()
    }
}

/**
 * Exception thrown when a skill is not found in the registry.
 */
class SkillNotFoundException(message: String) : Exception(message)

/**
 * Exception thrown when skill execution fails.
 */
class SkillExecutionException(
    val skillName: String,
    message: String,
    cause: Throwable? = null
) : Exception("Skill '$skillName': $message", cause)
