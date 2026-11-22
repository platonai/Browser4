package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Integration test for SkillBootstrap auto-loading in Spring context.
 *
 * This test verifies that skills are automatically loaded from classpath resources
 * (src/main/resources/skills/\*) when the AgenticContext is created. `browser4-cli` is the only
 * bundled skill since the demo skills were removed.
 */
class SkillBootstrapIntegrationTest {

    private lateinit var context: AgenticContext

    @BeforeEach
    fun setup() {
        val cx = AnnotationConfigApplicationContext(SkillBootstrap::class.java)
        context = GenericAgenticContext(cx, false)
    }

    @AfterEach
    fun cleanup() {
        context.close()
    }

    @Test
    fun skillsAreAutoLoadedInSpringContext() {
        val registry = SkillRegistry.instance

        // The bundled skill must be present.
        assertTrue(registry.contains("browser4-cli"), "Skill 'browser4-cli' should be auto-loaded")

        // The removed demo skills must not come back.
        listOf("web-scraping", "form-filling", "data-validation", "mcp-builder", "pdf", "weather")
            .forEach { removed ->
                assertFalse(registry.contains(removed), "Removed demo skill '$removed' must not be registered")
            }
    }

    @Test
    fun skillsAreAccessibleAfterContextCreation() {
        val registry = SkillRegistry.instance

        val cliSkill = registry.get("browser4-cli")
        assertNotNull(cliSkill)

        assertEquals("browser4-cli", cliSkill!!.metadata.name)
        assertEquals("1.0.0", cliSkill.metadata.version)
        assertTrue(cliSkill.metadata.description.isNotBlank())
    }

    @Test
    fun bundledSkillDependenciesAreSatisfied() {
        val registry = SkillRegistry.instance

        val cliSkill = registry.get("browser4-cli")
        assertNotNull(cliSkill)

        cliSkill!!.metadata.dependencies.forEach { depId ->
            assertTrue(registry.contains(depId), "Dependency $depId should be loaded")
        }
    }
}
