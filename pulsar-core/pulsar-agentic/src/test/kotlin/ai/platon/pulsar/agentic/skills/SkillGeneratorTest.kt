package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.mcp.MCPToolConverter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for SkillGenerator.
 */
class SkillGeneratorTest {

    @Test
    fun `toSkillTool should convert MCP tool correctly`() {
        val mcpTools = MCPToolConverter.getAllBuiltInMCPTools()
        val clickTool = mcpTools.find { it.name == "driver.click" }
        assertNotNull(clickTool)

        val skillTool = SkillGenerator.toSkillTool(clickTool!!)

        assertEquals("driver.click", skillTool.name)
        assertTrue(skillTool.mayNavigate)
        assertFalse(skillTool.readOnly)
    }

    @Test
    fun `toSkillTool should mark read-only tools correctly`() {
        val mcpTools = MCPToolConverter.getAllBuiltInMCPTools()
        val textContentTool = mcpTools.find { it.name == "driver.textContent" }
        assertNotNull(textContentTool)

        val skillTool = SkillGenerator.toSkillTool(textContentTool!!)

        assertFalse(skillTool.mayNavigate)
        assertTrue(skillTool.readOnly)
    }

    @Test
    fun `generateWebBrowsingSkill should include navigation tools`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()

        assertEquals("web_browsing", skill.name)
        assertEquals("Web Browsing", skill.displayName)
        assertEquals(SkillCategory.BROWSER_AUTOMATION, skill.category)
        assertTrue(skill.tools.any { it.name == "driver.navigateTo" })
        assertTrue(skill.tools.any { it.name == "driver.click" })
        assertTrue(skill.tools.any { it.name == "driver.scrollBy" })
        assertNotNull(skill.examples)
        assertTrue(skill.examples!!.isNotEmpty())
    }

    @Test
    fun `generateFormAutomationSkill should include form tools`() {
        val skill = SkillGenerator.generateFormAutomationSkill()

        assertEquals("form_automation", skill.name)
        assertEquals("Form Automation", skill.displayName)
        assertEquals(SkillCategory.FORM_AUTOMATION, skill.category)
        assertTrue(skill.tools.any { it.name == "driver.fill" })
        assertTrue(skill.tools.any { it.name == "driver.check" })
        assertTrue(skill.tools.any { it.name == "driver.uncheck" })
    }

    @Test
    fun `generateDataExtractionSkill should include extraction tools`() {
        val skill = SkillGenerator.generateDataExtractionSkill()

        assertEquals("data_extraction", skill.name)
        assertEquals("Data Extraction", skill.displayName)
        assertEquals(SkillCategory.DATA_EXTRACTION, skill.category)
        assertTrue(skill.tools.any { it.name == "driver.textContent" })
        assertTrue(skill.tools.any { it.name == "agent.extract" })
    }

    @Test
    fun `generateFileOperationsSkill should include file tools`() {
        val skill = SkillGenerator.generateFileOperationsSkill()

        assertEquals("file_operations", skill.name)
        assertEquals("File Operations", skill.displayName)
        assertEquals(SkillCategory.FILE_OPERATIONS, skill.category)
        assertTrue(skill.tools.any { it.name == "fs.writeString" })
        assertTrue(skill.tools.any { it.name == "fs.readString" })
    }

    @Test
    fun `generateSystemSkill should include system tools`() {
        val skill = SkillGenerator.generateSystemSkill()

        assertEquals("system", skill.name)
        assertEquals("System", skill.displayName)
        assertEquals(SkillCategory.SYSTEM, skill.category)
        assertTrue(skill.tools.any { it.name == "system.help" })
    }

    @Test
    fun `getAllPredefinedSkills should return all skills`() {
        val skills = SkillGenerator.getAllPredefinedSkills()

        assertTrue(skills.isNotEmpty())
        assertTrue(skills.any { it.name == "web_browsing" })
        assertTrue(skills.any { it.name == "form_automation" })
        assertTrue(skills.any { it.name == "data_extraction" })
        assertTrue(skills.any { it.name == "file_operations" })
        assertTrue(skills.any { it.name == "system" })
    }

    @Test
    fun `generateSkillFromToolNames should create custom skill`() {
        val skill = SkillGenerator.generateSkillFromToolNames(
            name = "custom_navigation",
            displayName = "Custom Navigation",
            description = "Custom navigation skill",
            instructions = "Use for navigation",
            toolNames = listOf("driver.navigateTo", "driver.goBack", "driver.goForward"),
        )

        assertEquals("custom_navigation", skill.name)
        assertEquals(3, skill.toolCount)
        assertEquals(SkillCategory.CUSTOM, skill.category)
    }

    @Test
    fun `generateSkill should create skill from MCP tools`() {
        val mcpTools = MCPToolConverter.getAllBuiltInMCPTools()
            .filter { it.name.startsWith("driver.scroll") }

        val skill = SkillGenerator.generateSkill(
            name = "scroll_skill",
            displayName = "Scroll Skill",
            description = "Scrolling skill",
            instructions = "Use for scrolling",
            mcpTools = mcpTools,
            category = SkillCategory.BROWSER_AUTOMATION,
        )

        assertEquals("scroll_skill", skill.name)
        assertTrue(skill.tools.all { it.name.startsWith("driver.scroll") })
    }

    @Test
    fun `skill examples should have valid steps`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()

        skill.examples?.forEach { example ->
            assertFalse(example.input.isBlank())
            assertTrue(example.steps.isNotEmpty())
            example.steps.forEach { step ->
                assertFalse(step.tool.isBlank())
                assertNotNull(step.arguments)
            }
        }
    }

    @Test
    fun `skill metadata should be set correctly`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()

        assertNotNull(skill.metadata)
        assertEquals("1.0.0", skill.metadata?.version)
        assertEquals("Browser4 Team", skill.metadata?.author)
        assertNotNull(skill.metadata?.tags)
    }
}
