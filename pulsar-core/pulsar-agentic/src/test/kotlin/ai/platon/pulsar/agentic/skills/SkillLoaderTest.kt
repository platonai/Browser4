package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillLoaderTest {

    private lateinit var registry: SkillRegistry
    private lateinit var loader: SkillLoader

    @BeforeEach
    fun setup() {
        registry = SkillRegistry()
        loader = SkillLoader(registry)
    }

    @Test
    fun `should load built-in skills`() {
        val count = loader.loadBuiltInSkills()
        
        // We have 3 built-in skills
        assertEquals(3, count)
        
        // Verify they're in the registry
        assertTrue(registry.contains("navigation"))
        assertTrue(registry.contains("data-extraction"))
        assertTrue(registry.contains("form-interaction"))
    }

    @Test
    fun `should verify navigation skill metadata`() {
        loader.loadBuiltInSkills()
        
        val skill = registry.get("navigation")
        assertNotNull(skill)
        
        val metadata = skill!!.metadata
        assertEquals("navigation", metadata.name)
        assertEquals("1.0.0", metadata.version)
        assertTrue(metadata.tags.contains("navigation"))
        assertTrue(metadata.requiredTools.isNotEmpty())
    }

    @Test
    fun `should verify data extraction skill metadata`() {
        loader.loadBuiltInSkills()
        
        val skill = registry.get("data-extraction")
        assertNotNull(skill)
        
        val metadata = skill!!.metadata
        assertEquals("data-extraction", metadata.name)
        assertTrue(metadata.tags.contains("extraction"))
    }

    @Test
    fun `should verify form interaction skill metadata`() {
        loader.loadBuiltInSkills()
        
        val skill = registry.get("form-interaction")
        assertNotNull(skill)
        
        val metadata = skill!!.metadata
        assertEquals("form-interaction", metadata.name)
        assertTrue(metadata.tags.contains("form"))
    }

    @Test
    fun `should use builder to load skills`() {
        val loader = SkillLoaderBuilder()
            .registry(registry)
            .loadBuiltInSkills(true)
            .build()

        // Verify skills are loaded
        assertTrue(registry.size() > 0)
        assertTrue(registry.contains("navigation"))
    }

    @Test
    fun `should not load built-in skills when disabled`() {
        val loader = SkillLoaderBuilder()
            .registry(registry)
            .loadBuiltInSkills(false)
            .build()

        // Verify no skills are loaded
        assertEquals(0, registry.size())
    }

    @Test
    fun `should handle loading non-existent class gracefully`() {
        val success = loader.loadSkillClass("com.nonexistent.FakeSkill")
        
        assertFalse(success)
        // Should not throw exception
    }

    @Test
    fun `should load skill by class name`() {
        val success = loader.loadSkillClass(
            "ai.platon.pulsar.agentic.skills.builtin.NavigationSkill"
        )
        
        assertTrue(success)
        assertTrue(registry.contains("navigation"))
    }

    @Test
    fun `should load multiple skill classes`() {
        val classNames = listOf(
            "ai.platon.pulsar.agentic.skills.builtin.NavigationSkill",
            "ai.platon.pulsar.agentic.skills.builtin.DataExtractionSkill"
        )
        
        val count = loader.loadSkillClasses(classNames)
        
        assertEquals(2, count)
        assertTrue(registry.contains("navigation"))
        assertTrue(registry.contains("data-extraction"))
    }
}
