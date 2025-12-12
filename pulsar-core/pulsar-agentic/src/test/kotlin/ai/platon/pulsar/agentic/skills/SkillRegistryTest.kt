package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.ActResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillRegistryTest {

    private lateinit var registry: SkillRegistry

    @BeforeEach
    fun setup() {
        registry = SkillRegistry()
    }

    @Test
    fun `should register a skill`() {
        val skill = TestSkill("test-skill")
        val registered = registry.register(skill)

        assertTrue(registered)
        assertTrue(registry.contains("test-skill"))
    }

    @Test
    fun `should get registered skill`() {
        val skill = TestSkill("test-skill")
        registry.register(skill)

        val retrieved = registry.get("test-skill")
        assertNotNull(retrieved)
        assertEquals("test-skill", retrieved?.metadata?.name)
    }

    @Test
    fun `should return null for non-existent skill`() {
        val skill = registry.get("non-existent")
        assertNull(skill)
    }

    @Test
    fun `should unregister a skill`() {
        val skill = TestSkill("test-skill")
        registry.register(skill)
        assertTrue(registry.contains("test-skill"))

        val unregistered = registry.unregister("test-skill")
        assertNotNull(unregistered)
        assertFalse(registry.contains("test-skill"))
    }

    @Test
    fun `should return all skill names`() {
        registry.register(TestSkill("skill-1"))
        registry.register(TestSkill("skill-2"))
        registry.register(TestSkill("skill-3"))

        val names = registry.getSkillNames()
        assertEquals(3, names.size)
        assertTrue(names.contains("skill-1"))
        assertTrue(names.contains("skill-2"))
        assertTrue(names.contains("skill-3"))
    }

    @Test
    fun `should find skills by tag`() {
        registry.register(TestSkill("skill-1", tags = setOf("web", "scraping")))
        registry.register(TestSkill("skill-2", tags = setOf("web", "navigation")))
        registry.register(TestSkill("skill-3", tags = setOf("data", "extraction")))

        val webSkills = registry.findByTag("web")
        assertEquals(2, webSkills.size)

        val scrapingSkills = registry.findByTag("scraping")
        assertEquals(1, scrapingSkills.size)
    }

    @Test
    fun `should search skills by query`() {
        registry.register(TestSkill("web-scraper", description = "Scrapes web pages"))
        registry.register(TestSkill("form-filler", description = "Fills web forms"))
        registry.register(TestSkill("data-extractor", description = "Extracts structured data"))

        val webResults = registry.search("web")
        assertEquals(2, webResults.size)

        val dataResults = registry.search("data")
        assertEquals(1, dataResults.size)
    }

    @Test
    fun `should get skill info with dependencies`() {
        registry.register(TestSkill("skill-a"))
        registry.register(TestSkill("skill-b", dependencies = setOf("skill-a")))

        val info = registry.getSkillInfo("skill-b")
        assertNotNull(info)
        assertTrue(info!!.isAvailable)
        assertTrue(info.missingDependencies.isEmpty())
    }

    @Test
    fun `should detect missing dependencies`() {
        registry.register(TestSkill("skill-b", dependencies = setOf("skill-a")))

        val info = registry.getSkillInfo("skill-b")
        assertNotNull(info)
        assertFalse(info!!.isAvailable)
        assertTrue(info.missingDependencies.contains("skill-a"))
    }

    @Test
    fun `should clear all skills`() {
        registry.register(TestSkill("skill-1"))
        registry.register(TestSkill("skill-2"))
        assertEquals(2, registry.size())

        registry.clear()
        assertEquals(0, registry.size())
    }

    @Test
    fun `should replace existing skill on re-registration`() {
        val skill1 = TestSkill("test-skill", version = "1.0.0")
        val skill2 = TestSkill("test-skill", version = "2.0.0")

        registry.register(skill1)
        val retrieved1 = registry.get("test-skill")
        assertEquals("1.0.0", retrieved1?.metadata?.version)

        registry.register(skill2)
        val retrieved2 = registry.get("test-skill")
        assertEquals("2.0.0", retrieved2?.metadata?.version)
    }

    // Helper test skill class
    private class TestSkill(
        name: String,
        version: String = "1.0.0",
        description: String = "Test skill",
        tags: Set<String> = emptySet(),
        dependencies: Set<String> = emptySet()
    ) : AbstractSkill(
        SkillMetadata(
            name = name,
            version = version,
            description = description,
            tags = tags,
            dependencies = dependencies
        )
    ) {
        override suspend fun execute(context: SkillContext): ActResult {
            return ActResult(
                success = true,
                message = "Test execution",
                action = metadata.name
            )
        }
    }
}
