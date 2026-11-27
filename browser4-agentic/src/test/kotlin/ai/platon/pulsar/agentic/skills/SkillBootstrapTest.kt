package ai.platon.pulsar.agentic.skills

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Tests for SkillBootstrap component.
 *
 * `browser4-cli` is the only skill bundled on the classpath; the former demo skills
 * (`web-scraping`, `form-filling`, `data-validation`, `pdf`, `mcp-builder`, `weather`) were
 * removed, so bootstrap must no longer register them. The registry also loads
 * `AgentPaths.SKILLS_DIR`, which is machine-specific — assertions below therefore check the
 * bundled skill by id instead of pinning the total count.
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
    @DisplayName("test bootstrap loads the bundled browser4-cli skill")
    fun testBootstrapLoadsBundledCliSkill() {
        bootstrap.initialize()

        assertTrue(registry.contains("browser4-cli"), "browser4-cli should be loaded from the classpath")
    }

    @Test
    @DisplayName("test bootstrap no longer loads the removed demo skills")
    fun testBootstrapDoesNotLoadRemovedDemoSkills() {
        bootstrap.initialize()

        listOf("web-scraping", "form-filling", "data-validation", "mcp-builder", "pdf", "weather")
            .forEach { removed ->
                assertFalse(registry.contains(removed), "Removed demo skill '$removed' must not be registered")
            }
    }

    @Test
    @DisplayName("test bootstrap can be called multiple times")
    fun testBootstrapCanBeCalledMultipleTimes() {
        bootstrap.initialize()
        val firstCount = registry.size()
        assertTrue(registry.contains("browser4-cli"))

        // Second initialization should clear and reload
        bootstrap.initialize()
        val secondCount = registry.size()

        // Should have same number of skills
        assertEquals(firstCount, secondCount)
        assertTrue(registry.contains("browser4-cli"))
    }

    @Test
    @DisplayName("test bundled skill has correct metadata")
    fun testBundledSkillHasCorrectMetadata() {
        bootstrap.initialize()

        val cliSkill = registry.get("browser4-cli")
        assertNotNull(cliSkill)
        assertEquals("browser4-cli", cliSkill!!.metadata.id)
        assertEquals("browser4-cli", cliSkill.metadata.name)
        assertEquals("1.0.0", cliSkill.metadata.version)
        assertTrue(cliSkill.metadata.description.isNotBlank())
        assertTrue(cliSkill.metadata.tags.contains("browser"))
        assertTrue(cliSkill.metadata.dependencies.isEmpty())
    }

    @Test
    @DisplayName("test loaded skills all expose non-blank metadata")
    fun testLoadedSkillsExposeMetadata() {
        bootstrap.initialize()

        val skills = registry.getAll()
        assertTrue(skills.isNotEmpty())
        skills.forEach { skill ->
            assertTrue(skill.metadata.id.isNotBlank())
            assertTrue(skill.metadata.name.isNotBlank())
            assertTrue(
                skill.metadata.version.matches(Regex("""\d+\.\d+\.\d+""")),
                "Version should be semver: ${skill.metadata.version}"
            )
        }
    }
}
