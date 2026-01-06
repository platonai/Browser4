package ai.platon.pulsar.agentic.skills

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for SkillRenderer.
 */
class SkillRendererTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `renderSkillJson should produce valid JSON`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val json = SkillRenderer.renderSkillJson(skill)

        assertFalse(json.isBlank())
        assertTrue(json.contains("web_browsing"))
        assertTrue(json.contains("Web Browsing"))

        // Verify it's valid JSON
        val parsed = objectMapper.readValue<Map<String, Any>>(json)
        assertEquals("web_browsing", parsed["name"])
    }

    @Test
    fun `renderSkillsJson should produce valid JSON array`() {
        val skills = SkillGenerator.getAllPredefinedSkills()
        val json = SkillRenderer.renderSkillsJson(skills)

        assertFalse(json.isBlank())
        assertTrue(json.startsWith("["))

        // Verify it's valid JSON array
        val parsed = objectMapper.readValue<List<Map<String, Any>>>(json)
        assertTrue(parsed.isNotEmpty())
    }

    @Test
    fun `renderSkillYaml should produce valid format`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val yaml = SkillRenderer.renderSkillYaml(skill)

        assertFalse(yaml.isBlank())
        // YAML-like format still contains the data
        assertTrue(yaml.contains("web_browsing"))
    }

    @Test
    fun `toClaudeDesktopSkill should convert correctly`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val desktopSkill = SkillRenderer.toClaudeDesktopSkill(skill)

        assertEquals(skill.name, desktopSkill.name)
        assertEquals(skill.description, desktopSkill.description)
        assertEquals(skill.instructions, desktopSkill.instructions)
        assertEquals(skill.tools.size, desktopSkill.tools.size)
    }

    @Test
    fun `renderClaudeDesktopConfig should produce valid format`() {
        val skills = SkillGenerator.getAllPredefinedSkills()
        val json = SkillRenderer.renderClaudeDesktopConfig(skills)

        assertFalse(json.isBlank())
        assertTrue(json.contains("\"skills\""))
        assertTrue(json.contains("\"version\""))

        // Verify structure
        val parsed = objectMapper.readValue<Map<String, Any>>(json)
        assertTrue(parsed.containsKey("skills"))
        assertTrue(parsed.containsKey("version"))
    }

    @Test
    fun `renderClaudeDesktopSkill should produce valid format`() {
        val skill = SkillGenerator.generateFormAutomationSkill()
        val json = SkillRenderer.renderClaudeDesktopSkill(skill)

        assertFalse(json.isBlank())
        assertTrue(json.contains("form_automation"))
        assertTrue(json.contains("tools"))
    }

    @Test
    fun `renderSkillToolsMcp should produce MCP format`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val json = SkillRenderer.renderSkillToolsMcp(skill)

        assertFalse(json.isBlank())
        assertTrue(json.startsWith("["))

        // Verify it contains tool definitions
        assertTrue(json.contains("inputSchema"))
        assertTrue(json.contains("driver."))
    }

    @Test
    fun `renderSkillMarkdown should produce documentation`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val markdown = SkillRenderer.renderSkillMarkdown(skill)

        assertFalse(markdown.isBlank())
        assertTrue(markdown.contains("# Web Browsing"))
        assertTrue(markdown.contains("## Instructions"))
        assertTrue(markdown.contains("## Tools"))
        assertTrue(markdown.contains("### driver."))
    }

    @Test
    fun `renderSkillMarkdown should include examples section`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val markdown = SkillRenderer.renderSkillMarkdown(skill)

        assertTrue(markdown.contains("## Examples"))
        assertTrue(markdown.contains("**Input:**"))
        assertTrue(markdown.contains("**Steps:**"))
    }

    @Test
    fun `renderSkillMarkdown should include metadata section`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val markdown = SkillRenderer.renderSkillMarkdown(skill)

        assertTrue(markdown.contains("## Metadata"))
        assertTrue(markdown.contains("**Version:**"))
        assertTrue(markdown.contains("**Author:**"))
    }

    @Test
    fun `renderPackageJson should produce valid package format`() {
        val pkg = SkillPackage(
            name = "browser4-skills",
            version = "1.0.0",
            description = "Browser4 skill package",
            skills = SkillGenerator.getAllPredefinedSkills(),
            metadata = SkillPackageMetadata(
                author = "Browser4 Team",
                license = "Apache-2.0",
            ),
        )

        val json = SkillRenderer.renderPackageJson(pkg)

        assertFalse(json.isBlank())
        assertTrue(json.contains("browser4-skills"))
        assertTrue(json.contains("1.0.0"))
        assertTrue(json.contains("skills"))
    }

    @Test
    fun `Claude Desktop tool should have correct input schema structure`() {
        val skill = SkillGenerator.generateFormAutomationSkill()
        val desktopSkill = SkillRenderer.toClaudeDesktopSkill(skill)

        desktopSkill.tools.forEach { tool ->
            assertTrue(tool.inputSchema.containsKey("type"))
            assertEquals("object", tool.inputSchema["type"])
        }
    }

    @Test
    fun `Claude Desktop examples should format steps correctly`() {
        val skill = SkillGenerator.generateWebBrowsingSkill()
        val desktopSkill = SkillRenderer.toClaudeDesktopSkill(skill)

        assertNotNull(desktopSkill.examples)
        desktopSkill.examples?.forEach { example ->
            assertFalse(example.input.isBlank())
            assertTrue(example.steps.isNotEmpty())
            // Steps should be in format "tool(args)"
            example.steps.forEach { step ->
                assertTrue(step.contains("("))
                assertTrue(step.contains(")"))
            }
        }
    }
}
