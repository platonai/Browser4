package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for Claude Skill types.
 */
class SkillTypesTest {

    @Test
    fun `ClaudeSkill should have correct tool count`() {
        val skill = ClaudeSkill(
            name = "test_skill",
            displayName = "Test Skill",
            description = "A test skill",
            instructions = "Test instructions",
            tools = listOf(
                SkillTool(
                    name = "test.tool1",
                    description = "Tool 1",
                    inputSchema = SkillToolInputSchema(),
                ),
                SkillTool(
                    name = "test.tool2",
                    description = "Tool 2",
                    inputSchema = SkillToolInputSchema(),
                ),
            ),
        )

        assertEquals(2, skill.toolCount)
        assertEquals(listOf("test.tool1", "test.tool2"), skill.toolNames)
    }

    @Test
    fun `SkillTool should convert to MCP format`() {
        val tool = SkillTool(
            name = "driver.click",
            description = "Click an element",
            inputSchema = SkillToolInputSchema(
                type = "object",
                properties = mapOf(
                    "selector" to SkillPropertySchema(type = "string", description = "CSS selector"),
                ),
                required = listOf("selector"),
            ),
        )

        val mcpTool = tool.toMCPToolDefinition()

        assertEquals("driver.click", mcpTool.name)
        assertEquals("Click an element", mcpTool.description)
        assertEquals("object", mcpTool.inputSchema.type)
        assertEquals(listOf("selector"), mcpTool.inputSchema.required)
    }

    @Test
    fun `SkillExample should contain steps`() {
        val example = SkillExample(
            input = "Click the button",
            output = "Button clicked",
            steps = listOf(
                SkillExampleStep(
                    tool = "driver.click",
                    arguments = mapOf("selector" to "#button"),
                    description = "Click the button",
                ),
            ),
            tags = listOf("click", "button"),
        )

        assertEquals(1, example.steps.size)
        assertEquals("driver.click", example.steps[0].tool)
        assertEquals("#button", example.steps[0].arguments["selector"])
    }

    @Test
    fun `SkillMetadata should have default version`() {
        val metadata = SkillMetadata()
        assertEquals("1.0.0", metadata.version)
        assertFalse(metadata.experimental)
        assertFalse(metadata.deprecated)
    }

    @Test
    fun `SkillCategory should have correct display names`() {
        assertEquals("Browser Automation", SkillCategory.BROWSER_AUTOMATION.displayName)
        assertEquals("Data Extraction", SkillCategory.DATA_EXTRACTION.displayName)
        assertEquals("Form Automation", SkillCategory.FORM_AUTOMATION.displayName)
        assertEquals("File Operations", SkillCategory.FILE_OPERATIONS.displayName)
    }

    @Test
    fun `ClaudeDesktopSkillsConfig should contain skills`() {
        val config = ClaudeDesktopSkillsConfig(
            skills = listOf(
                ClaudeDesktopSkill(
                    name = "test",
                    description = "Test skill",
                    instructions = "Instructions",
                    tools = emptyList(),
                ),
            ),
            version = "1.0",
        )

        assertEquals(1, config.skills.size)
        assertEquals("1.0", config.version)
    }

    @Test
    fun `SkillPackage should contain skills`() {
        val pkg = SkillPackage(
            name = "browser4-skills",
            version = "1.0.0",
            description = "Browser4 skill package",
            skills = emptyList(),
            metadata = SkillPackageMetadata(
                author = "Browser4 Team",
                license = "MIT",
            ),
        )

        assertEquals("browser4-skills", pkg.name)
        assertEquals("1.0.0", pkg.version)
        assertEquals("Browser4 Team", pkg.metadata?.author)
    }
}
