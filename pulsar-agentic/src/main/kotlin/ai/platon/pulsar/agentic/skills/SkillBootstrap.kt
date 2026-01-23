package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.skills.examples.DataValidationSkill
import ai.platon.pulsar.agentic.skills.examples.FormFillingSkill
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
 * 1. Loading example skills (WebScrapingSkill, FormFillingSkill, DataValidationSkill)
 * 2. Loading skills from AgentPaths.SKILLS_DIR directory
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
        
        // Load example skills
        loadExampleSkills(context)
        
        // Load skills from directory
        loadSkillsFromDirectory(context)
        
        logger.info("✓ Skills system initialized successfully. Total skills: {}", registry.size())
    }

    /**
     * Load example skills from code.
     */
    private suspend fun loadExampleSkills(context: SkillContext) {
        logger.info("Loading example skills...")
        
        val exampleSkills = listOf(
            WebScrapingSkill(),
            FormFillingSkill(),
            DataValidationSkill()
        )
        
        val results = loader.loadAll(exampleSkills, context)
        
        val successCount = results.values.count { it }
        val failureCount = results.size - successCount
        
        logger.info("✓ Loaded {} example skills ({} succeeded, {} failed)", 
            results.size, successCount, failureCount)
        
        if (failureCount > 0) {
            val failed = results.filterValues { !it }.keys
            logger.warn("Failed to load example skills: {}", failed.joinToString())
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
            
            // For now, we just log the definitions
            // In a full implementation, we would need to instantiate skills from definitions
            // This would require a skill factory or dynamic loading mechanism
            definitions.forEach { definition ->
                logger.info("  - Skill: {} ({})", definition.name, definition.skillId)
            }
            
            logger.info("Note: Directory-based skill loading requires skill factory implementation")
            
        } catch (e: NoClassDefFoundError) {
            logger.warn("AgentPaths not available: {}", e.message)
        } catch (e: ExceptionInInitializerError) {
            logger.warn("AgentPaths initialization failed: {}", e.message)
        } catch (e: Exception) {
            logger.warn("Failed to load skills from directory: {}", e.message)
        }
    }
}
