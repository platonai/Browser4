package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for SkillRegistry.
 */
class SkillRegistryTest {

    private lateinit var registry: SkillRegistry

    @BeforeEach
    fun setUp() {
        registry = SkillRegistry.instance
        registry.reset()
    }

    @Test
    fun `registry should have predefined skills on init`() {
        val skills = registry.getAllSkills()
        assertTrue(skills.isNotEmpty())
        assertTrue(registry.hasSkill("web_browsing"))
        assertTrue(registry.hasSkill("form_automation"))
    }

    @Test
    fun `getSkill should return skill by name`() {
        val skill = registry.getSkill("web_browsing")
        assertNotNull(skill)
        assertEquals("web_browsing", skill?.name)
    }

    @Test
    fun `getSkill should return null for unknown skill`() {
        val skill = registry.getSkill("unknown_skill")
        assertNull(skill)
    }

    @Test
    fun `registerSkill should add new skill`() {
        val customSkill = ClaudeSkill(
            name = "custom_test_skill",
            displayName = "Custom Test",
            description = "A custom test skill",
            instructions = "Test instructions",
            tools = emptyList(),
        )

        registry.registerSkill(customSkill)

        assertTrue(registry.hasSkill("custom_test_skill"))
        assertEquals(customSkill, registry.getSkill("custom_test_skill"))

        // Cleanup
        registry.unregisterSkill("custom_test_skill")
    }

    @Test
    fun `registerSkill should throw for duplicate name`() {
        val skill = ClaudeSkill(
            name = "web_browsing",
            displayName = "Duplicate",
            description = "Duplicate skill",
            instructions = "Instructions",
            tools = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.registerSkill(skill)
        }
    }

    @Test
    fun `registerSkill should throw for blank name`() {
        val skill = ClaudeSkill(
            name = "",
            displayName = "Blank Name",
            description = "Skill with blank name",
            instructions = "Instructions",
            tools = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.registerSkill(skill)
        }
    }

    @Test
    fun `unregisterSkill should remove skill`() {
        val customSkill = ClaudeSkill(
            name = "to_be_removed",
            displayName = "To Be Removed",
            description = "Will be removed",
            instructions = "Instructions",
            tools = emptyList(),
        )

        registry.registerSkill(customSkill)
        assertTrue(registry.hasSkill("to_be_removed"))

        val result = registry.unregisterSkill("to_be_removed")
        assertTrue(result)
        assertFalse(registry.hasSkill("to_be_removed"))
    }

    @Test
    fun `unregisterSkill should return false for unknown skill`() {
        val result = registry.unregisterSkill("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `getSkillsByCategory should filter correctly`() {
        val browserSkills = registry.getSkillsByCategory(SkillCategory.BROWSER_AUTOMATION)
        assertTrue(browserSkills.isNotEmpty())
        assertTrue(browserSkills.all { it.category == SkillCategory.BROWSER_AUTOMATION })
    }

    @Test
    fun `searchSkills should find by name`() {
        val results = registry.searchSkills("browsing")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name == "web_browsing" })
    }

    @Test
    fun `searchSkills should find by description`() {
        val results = registry.searchSkills("form")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name == "form_automation" })
    }

    @Test
    fun `searchSkills should be case insensitive`() {
        val results1 = registry.searchSkills("BROWSING")
        val results2 = registry.searchSkills("browsing")
        assertEquals(results1.size, results2.size)
    }

    @Test
    fun `findSkillsWithTool should find skills containing tool`() {
        val results = registry.findSkillsWithTool("driver.click")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { skill ->
            skill.tools.any { it.name == "driver.click" }
        })
    }

    @Test
    fun `getSkillNames should return all skill names`() {
        val names = registry.getSkillNames()
        assertTrue(names.contains("web_browsing"))
        assertTrue(names.contains("form_automation"))
    }

    @Test
    fun `clear should remove all skills`() {
        registry.clear()
        assertTrue(registry.getAllSkills().isEmpty())
        assertFalse(registry.hasSkill("web_browsing"))

        // Reset for other tests
        registry.reset()
    }

    @Test
    fun `reset should restore predefined skills`() {
        registry.clear()
        assertTrue(registry.getAllSkills().isEmpty())

        registry.reset()
        assertTrue(registry.getAllSkills().isNotEmpty())
        assertTrue(registry.hasSkill("web_browsing"))
    }

    @Test
    fun `getStats should return correct statistics`() {
        val stats = registry.getStats()

        assertTrue(stats.containsKey("skills"))
        assertTrue(stats.containsKey("totalTools"))
        assertTrue(stats.containsKey("byCategory"))

        val skillCount = stats["skills"] as Int
        assertTrue(skillCount > 0)
    }
}
