package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.context.DefaultClassPathXmlAgenticContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Integration test for SkillBootstrap auto-loading in Spring context.
 * 
 * This test verifies that skills are automatically loaded when the AgenticContext is created.
 */
class SkillBootstrapIntegrationTest {

    private lateinit var context: DefaultClassPathXmlAgenticContext

    @BeforeEach
    fun setup() {
        // Create the Spring context which should trigger SkillBootstrap initialization
        context = DefaultClassPathXmlAgenticContext()
    }

    @AfterEach
    fun cleanup() {
        context.close()
    }

    @Test
    fun `test skills are auto-loaded in Spring context`() {
        // Get the registry (singleton)
        val registry = SkillRegistry.instance
        
        // Verify that example skills are loaded
        // Note: The actual count may vary if AgentPaths is available and loads more skills
        assertTrue(registry.size() >= 3, "At least 3 example skills should be auto-loaded")
        
        // Verify specific example skills
        assertTrue(registry.contains("web-scraping"), "WebScrapingSkill should be auto-loaded")
        assertTrue(registry.contains("form-filling"), "FormFillingSkill should be auto-loaded") 
        assertTrue(registry.contains("data-validation"), "DataValidationSkill should be auto-loaded")
    }

    @Test
    fun `test skills are accessible after context creation`() {
        val registry = SkillRegistry.instance
        
        // Get a skill
        val webScrapingSkill = registry.get("web-scraping")
        Assertions.assertNotNull(webScrapingSkill)
        
        // Verify metadata
        assertEquals("Web Scraping", webScrapingSkill!!.metadata.name)
        assertEquals("1.0.0", webScrapingSkill.metadata.version)
    }

    @Test
    fun `test skill dependencies are satisfied`() {
        val registry = SkillRegistry.instance
        
        // FormFillingSkill depends on WebScrapingSkill
        val formFillingSkill = registry.get("form-filling")
        Assertions.assertNotNull(formFillingSkill)
        
        // Verify the dependency is satisfied
        val dependencies = formFillingSkill!!.metadata.dependencies
        assertTrue(dependencies.contains("web-scraping"))
        
        // Verify the dependency is actually loaded
        dependencies.forEach { depId ->
            assertTrue(registry.contains(depId), "Dependency $depId should be loaded")
        }
    }
}
