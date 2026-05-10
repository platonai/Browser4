package ai.platon.pulsar.agentic.skills

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName

/**
 * Tests for SkillBootstrap component.
 */
class SkillBootstrapTest {

    private lateinit var bootstrap: SkillBootstrap
    private lateinit var registry: SkillRegistry
    private lateinit var context: SkillContext

    @BeforeEach
    fun setup() = runBlocking {
        registry = SkillRegistry.instance
        context = SkillContext(sessionId = "test-bootstrap")
        registry.clear(context)
        bootstrap = SkillBootstrap()
    }

    @AfterEach
    fun cleanup() = runBlocking {
        registry.clear(context)
    }

    @Test
        @DisplayName("test bootstrap initializes example skills")
    fun testBootstrapInitializesExampleSkills() {
        bootstrap.initialize()

        // Verify that example skills are loaded
        assertTrue(registry.contains("web-scraping"), "WebScrapingSkill should be loaded")
        assertTrue(registry.contains("form-filling"), "FormFillingSkill should be loaded")
        assertTrue(registry.contains("data-validation"), "DataValidationSkill should be loaded")

        // Verify skill count
        assertTrue(registry.size() >= 3, "At least 3 skills should be loaded")
    }

    @Test
        @DisplayName("test bootstrap loads skills in correct order")
    fun testBootstrapLoadsSkillsInCorrectOrder() {
        bootstrap.initialize()

        // form-filling depends on web-scraping, so both should be loaded
        assertTrue(registry.contains("web-scraping"))
        assertTrue(registry.contains("form-filling"))

        // Verify dependency is satisfied
        val formFilling = registry.get("form-filling")
        Assertions.assertNotNull(formFilling)
        assertTrue(formFilling!!.metadata.dependencies.contains("web-scraping"))
    }

    @Test
        @DisplayName("test bootstrap can be called multiple times")
    fun testBootstrapCanBeCalledMultipleTimes() {
        // First initialization
        bootstrap.initialize()
        val firstCount = registry.size()
        assertTrue(firstCount >= 3)

        // Second initialization should clear and reload
        bootstrap.initialize()
        val secondCount = registry.size()

        // Should have same number of skills
        assertEquals(firstCount, secondCount)

        // Skills should still be accessible
        assertTrue(registry.contains("web-scraping"))
        assertTrue(registry.contains("form-filling"))
        assertTrue(registry.contains("data-validation"))
    }

    @Test
        @DisplayName("test bootstrap loads all example skills successfully")
    fun testBootstrapLoadsAllExampleSkillsSuccessfully() {
        bootstrap.initialize()

        // Get all loaded skills
        val skills = registry.getAll()

        // Should have at least the 3 example skills
        assertTrue(skills.size >= 3)

        // Verify each example skill can be executed
        val exampleSkillIds = listOf("web-scraping", "form-filling", "data-validation")
        exampleSkillIds.forEach { skillId ->
            val skill = registry.get(skillId)
            Assertions.assertNotNull(skill, "Skill $skillId should exist")
            assertEquals(skillId, skill!!.metadata.id)
        }
    }

    @Test
        @DisplayName("test example skills have correct metadata")
    fun testExampleSkillsHaveCorrectMetadata() {
        bootstrap.initialize()

        // Verify web-scraping skill
        val webScraping = registry.get("web-scraping")
        Assertions.assertNotNull(webScraping)
        assertEquals("Web Scraping", webScraping!!.metadata.name)
        assertEquals("1.0.0", webScraping.metadata.version)
        assertEquals("Browser4", webScraping.metadata.author)

        // Verify form-filling skill
        val formFilling = registry.get("form-filling")
        Assertions.assertNotNull(formFilling)
        assertEquals("Form Filling", formFilling!!.metadata.name)
        assertTrue(formFilling.metadata.dependencies.isNotEmpty())

        // Verify data-validation skill
        val dataValidation = registry.get("data-validation")
        Assertions.assertNotNull(dataValidation)
        assertEquals("Data Validation", dataValidation!!.metadata.name)
    }
}
