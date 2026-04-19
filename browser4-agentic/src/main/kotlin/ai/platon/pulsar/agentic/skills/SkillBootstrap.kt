package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.skills.examples.WebScrapingSkill
import ai.platon.pulsar.common.getLogger
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Bootstrap component for automatically loading skills on system startup.
 *
 * This component is responsible for:
 * 1. Loading built-in skills from classpath resources under `skills/ * /SKILL.md`
 * 2. Loading example programmatic skills (e.g., WebScrapingSkill with skill.debug.scraping domain)
 * 3. Optionally loading additional/override skills from `AgentPaths.SKILLS_DIR`
 *
 * The component is automatically initialized by Spring on application startup
 * using the @PostConstruct annotation.
 */
@Component
@Lazy(false)  // Ensure eager initialization to load skills on startup
class SkillBootstrap {
    private val logger = getLogger(this)
    private val registry = SkillRegistry.instance
    private val loader = SkillLoader(registry)
    private val definitionLoader = SkillDefinitionLoader()

    /**
     * Initialize skills on system startup.
     * This method is called automatically by Spring after bean construction.
     */
    @PostConstruct
    fun initialize() = runBlocking {
        logger.info("Initializing skills system...")

        val context = SkillContext(sessionId = "system-bootstrap")

        // Clear any existing skills
        registry.clear(context)

        // Load skills from classpath resources first (built-in skills)
        loadSkillsFromResources(context)

        // Load example programmatic skills (e.g., WebScrapingSkill with skill.debug.scraping domain)
        loadExampleSkills(context)

        // Optionally load skills from directory (user-provided/overrides)
        loadSkillsFromDirectory(context)

        logger.info("✓ Skills system initialized successfully. Total skills: {}", registry.size())
    }

    /**
     * Load skills shipped with the application from classpath resources.
     */
    private suspend fun loadSkillsFromResources(context: SkillContext) {
        logger.info("Loading skills from classpath resources: skills/")

        val definitions = definitionLoader.loadFromResources("skills")
        if (definitions.isEmpty()) {
            logger.info("No skill definitions found in classpath resources: skills/")
            return
        }

        val skills = definitions
            .sortedBy { it.skillId }
            .map { DefinitionBackedSkill(it, DefinitionBackedSkill.Origin.Classpath("skills/${it.skillId}")) }

        val results = loader.loadAll(skills, context)

        val successCount = results.values.count { it }
        val failureCount = results.size - successCount

        logger.info(
            "✓ Loaded {} resource skills ({} succeeded, {} failed)",
            results.size,
            successCount,
            failureCount
        )

        if (failureCount > 0) {
            val failed = results.filterValues { !it }.keys
            logger.warn("Failed to load resource skills: {}", failed.joinToString())
        }
    }

    /**
     * Load example programmatic skills.
     *
     * This registers the WebScrapingSkill which provides the skill.debug.scraping domain.
     * These skills are registered only if not already present (to allow resource-backed skills to take precedence).
     */
    private suspend fun loadExampleSkills(context: SkillContext) {
        logger.info("Loading example programmatic skills...")

        val exampleSkills = listOf(
            WebScrapingSkill()
        )

        var loaded = 0
        for (skill in exampleSkills) {
            // Skip if a skill with the same ID is already registered (resource-backed takes precedence)
            if (registry.contains(skill.metadata.id)) {
                logger.debug("Skipping example skill '{}' - already registered", skill.metadata.id)
                continue
            }

            try {
                registry.register(skill, context)
                loaded++
            } catch (e: Exception) {
                logger.warn("Failed to register example skill '{}': {}", skill.metadata.id, e.message)
            }
        }

        if (loaded > 0) {
            logger.info("✓ Loaded {} example programmatic skills", loaded)
        }
    }

    /**
     * Load skills from AgentPaths.SKILLS_DIR directory.
     */
    private suspend fun loadSkillsFromDirectory(context: SkillContext) {
        try {
            val skillsDir = AgentPaths.SKILLS_DIR
            logger.info("Loading skills from directory: {}", skillsDir)

            val definitions = definitionLoader.loadFromDirectory(skillsDir)

            if (definitions.isEmpty()) {
                logger.info("No skill definitions found in directory: {}", skillsDir)
                return
            }

            logger.info("Found {} skill definitions in directory", definitions.size)

            val skills = definitions
                .sortedBy { it.skillId }
                .map { DefinitionBackedSkill(it, DefinitionBackedSkill.Origin.FileSystem(skillsDir.resolve(it.skillId))) }

            val results = loader.loadAll(skills, context)
            val successCount = results.values.count { it }
            val failureCount = results.size - successCount

            logger.info(
                "✓ Loaded {} directory skills ({} succeeded, {} failed)",
                results.size,
                successCount,
                failureCount
            )

            if (failureCount > 0) {
                val failed = results.filterValues { !it }.keys
                logger.warn("Failed to load directory skills: {}", failed.joinToString())
            }
        } catch (e: NoClassDefFoundError) {
            logger.warn("AgentPaths not available: {}", e.message)
        } catch (e: ExceptionInInitializerError) {
            logger.warn("AgentPaths initialization failed: {}", e.message)
        } catch (e: Exception) {
            logger.warn("Failed to load skills from directory: {}", e.message)
        }
    }
}
