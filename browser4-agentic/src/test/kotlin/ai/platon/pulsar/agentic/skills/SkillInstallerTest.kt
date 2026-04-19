package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for SkillInstaller and document-driven installation capability.
 */
class SkillInstallerTest {

    private lateinit var registry: SkillRegistry
    private lateinit var context: SkillContext
    private lateinit var installer: SkillInstaller
    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() = runBlocking {
        registry = SkillRegistry.instance
        context = SkillContext(sessionId = "test-installer")
        registry.clear(context)
        installer = SkillInstaller(registry)
        tempDir = Files.createTempDirectory("skill-installer-test")
    }

    @AfterEach
    fun cleanup() = runBlocking {
        registry.clear(context)
        // Clean up temp directory
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @DisplayName("test install from valid skill directory")
    fun testInstallFromValidSkillDirectory() = runBlocking {
        // Create a test skill directory with SKILL.md
        val skillDir = createTestSkillDirectory("test-install-skill")

        val result = installer.install(skillDir, context)

        assertTrue(result.success, "Installation should succeed: ${result.message}")
        assertEquals("test-install-skill", result.skillId)
        assertTrue(registry.contains("test-install-skill"), "Skill should be registered")
        assertNotNull(result.deployedPath, "Deployed path should be set")
    }

    @Test
    @DisplayName("test install fails when SKILL.md is missing")
    fun testInstallFailsWhenSkillMdMissing() = runBlocking {
        val emptyDir = tempDir.resolve("empty-skill")
        Files.createDirectories(emptyDir)

        val result = installer.install(emptyDir, context)

        assertFalse(result.success)
        assertTrue(result.message.contains("SKILL.md not found"))
    }

    @Test
    @DisplayName("test install fails when source directory does not exist")
    fun testInstallFailsWhenSourceDoesNotExist() = runBlocking {
        val nonExistentDir = tempDir.resolve("nonexistent")

        val result = installer.install(nonExistentDir, context)

        assertFalse(result.success)
        assertTrue(result.message.contains("does not exist"))
    }

    @Test
    @DisplayName("test install fails when skill already exists without overwrite")
    fun testInstallFailsWhenAlreadyExistsWithoutOverwrite() = runBlocking {
        val skillDir = createTestSkillDirectory("test-dup-skill")

        // First install
        val first = installer.install(skillDir, context)
        assertTrue(first.success, "First install should succeed: ${first.message}")

        // Second install should fail
        val second = installer.install(skillDir, context)
        assertFalse(second.success)
        assertTrue(second.message.contains("already installed"))
    }

    @Test
    @DisplayName("test install with overwrite replaces existing skill")
    fun testInstallWithOverwriteReplacesExisting() = runBlocking {
        val skillDir = createTestSkillDirectory("test-overwrite-skill")

        // First install
        val first = installer.install(skillDir, context)
        assertTrue(first.success, "First install should succeed: ${first.message}")

        // Second install with overwrite
        val second = installer.install(skillDir, context, overwrite = true)
        assertTrue(second.success, "Overwrite install should succeed: ${second.message}")
        assertTrue(registry.contains("test-overwrite-skill"))
    }

    @Test
    @DisplayName("test uninstall removes skill")
    fun testUninstallRemovesSkill() = runBlocking {
        val skillDir = createTestSkillDirectory("test-uninstall-skill")

        // Install first
        val installResult = installer.install(skillDir, context)
        assertTrue(installResult.success, "Install should succeed: ${installResult.message}")
        assertTrue(registry.contains("test-uninstall-skill"))

        // Uninstall
        val uninstallResult = installer.uninstall("test-uninstall-skill", context)
        assertTrue(uninstallResult.success, "Uninstall should succeed: ${uninstallResult.message}")
        assertFalse(registry.contains("test-uninstall-skill"))
    }

    @Test
    @DisplayName("test parseInstallSteps extracts numbered steps")
    fun testParseInstallStepsExtractsNumberedSteps() {
        val content = """
            ---
            name: test-skill
            description: A test skill
            ---
            
            # Test Skill
            
            ## Installation
            
            1. Download the package
            2. Extract to your skills directory
            3. Run the setup script
            
            ## Parameters
        """.trimIndent()

        val steps = installer.parseInstallSteps(content)

        assertEquals(3, steps.size)
        assertEquals("Download the package", steps[0])
        assertEquals("Extract to your skills directory", steps[1])
        assertEquals("Run the setup script", steps[2])
    }

    @Test
    @DisplayName("test parseInstallSteps extracts bulleted steps")
    fun testParseInstallStepsExtractsBulletedSteps() {
        val content = """
            # Test Skill
            
            ## Setup
            
            - Install Python 3.8+
            - Run pip install browser4-sdk
            * Configure API key in settings
            
            ## Usage
        """.trimIndent()

        val steps = installer.parseInstallSteps(content)

        assertEquals(3, steps.size)
        assertEquals("Install Python 3.8+", steps[0])
        assertEquals("Run pip install browser4-sdk", steps[1])
        assertEquals("Configure API key in settings", steps[2])
    }

    @Test
    @DisplayName("test parseInstallSteps returns empty for no install section")
    fun testParseInstallStepsReturnsEmptyForNoInstallSection() {
        val content = """
            # Test Skill
            
            ## Description
            
            A test skill description.
            
            ## Parameters
        """.trimIndent()

        val steps = installer.parseInstallSteps(content)
        assertTrue(steps.isEmpty())
    }

    @Test
    @DisplayName("test parseInstallSteps handles Getting Started section")
    fun testParseInstallStepsHandlesGettingStartedSection() {
        val content = """
            # Test Skill
            
            ## Getting Started
            
            1. Clone the repository
            2. Install dependencies
            
            ## Next Steps
        """.trimIndent()

        val steps = installer.parseInstallSteps(content)

        assertEquals(2, steps.size)
        assertEquals("Clone the repository", steps[0])
        assertEquals("Install dependencies", steps[1])
    }

    @Test
    @DisplayName("test readDocumentation returns skill MD content")
    fun testReadDocumentationReturnsContent() = runBlocking {
        // Initialize with built-in skills
        SkillBootstrap().initialize()

        val doc = installer.readDocumentation("web-scraping")

        assertNotNull(doc)
        assertTrue(doc!!.contains("Web Scraping"))
    }

    @Test
    @DisplayName("test readDocumentation returns null for unknown skill")
    fun testReadDocumentationReturnsNullForUnknown() = runBlocking {
        val doc = installer.readDocumentation("nonexistent-skill")
        assertNull(doc)
    }

    @Test
    @DisplayName("test listInstalled returns correct skills")
    fun testListInstalledReturnsCorrectSkills() = runBlocking {
        SkillBootstrap().initialize()

        val installed = installer.listInstalled()

        assertTrue(installed.isNotEmpty())
        assertTrue(installed.any { it["id"] == "web-scraping" })
    }

    @Test
    @DisplayName("test install via SkillToolExecutor install tool")
    fun testInstallViaToolExecutor() = runBlocking {
        val skillDir = createTestSkillDirectory("test-tool-install")

        val executor = SkillToolExecutor(registry)
        val target = SkillToolTarget(context, registry)

        val result = executor.callFunctionOn(
            domain = "skill",
            functionName = "install",
            args = mapOf("sourceDir" to skillDir.toString(), "overwrite" to false),
            receiver = target
        )

        assertTrue(result is SkillInstaller.InstallResult)
        val installResult = result as SkillInstaller.InstallResult
        assertTrue(installResult.success, "Tool install should succeed: ${installResult.message}")
        assertTrue(registry.contains("test-tool-install"))
    }

    @Test
    @DisplayName("test readDocumentation via SkillToolExecutor tool")
    fun testReadDocumentationViaToolExecutor() = runBlocking {
        SkillBootstrap().initialize()

        val executor = SkillToolExecutor(registry)
        val target = SkillToolTarget(context, registry)

        val result = executor.callFunctionOn(
            domain = "skill",
            functionName = "readDocumentation",
            args = mapOf("id" to "web-scraping"),
            receiver = target
        )

        assertTrue(result is String)
        assertTrue((result as String).contains("Web Scraping"))
    }

    @Test
    @DisplayName("test SkillDefinitionLoader parses installSteps from YAML frontmatter SKILL.md")
    fun testDefinitionLoaderParsesInstallSteps() {
        // Create a skill directory with installation steps in SKILL.md
        val skillDir = tempDir.resolve("test-loader-skill")
        Files.createDirectories(skillDir)
        val skillMd = skillDir.resolve("SKILL.md")
        Files.writeString(skillMd, """
            ---
            name: test-loader-skill
            description: A test skill with install steps
            ---
            
            # Test Loader Skill
            
            ## Description
            
            A skill for testing install step parsing.
            
            ## Installation
            
            1. Download the binary
            2. Add to PATH
            3. Verify with `test-loader-skill --version`
            
            ## Parameters
            
            None
        """.trimIndent())

        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromDirectory(tempDir)

        val def = definitions.find { it.skillId == "test-loader-skill" }
        assertNotNull(def, "Definition should be loaded")
        assertEquals(3, def!!.installSteps.size)
        assertEquals("Download the binary", def.installSteps[0])
        assertEquals("Add to PATH", def.installSteps[1])
        assertEquals("Verify with `test-loader-skill --version`", def.installSteps[2])
    }

    /**
     * Create a test skill directory with a valid SKILL.md.
     */
    private fun createTestSkillDirectory(skillName: String): Path {
        val skillDir = tempDir.resolve(skillName)
        Files.createDirectories(skillDir)

        val skillMd = skillDir.resolve("SKILL.md")
        Files.writeString(skillMd, """
---
name: $skillName
description: A test skill for installation testing
metadata:
  version: "1.0.0"
  author: Test
  tags: "test"
---

# ${skillName.replace("-", " ").replaceFirstChar { it.uppercase() }}

## Description

A test skill for automated installation testing.

## Installation

1. Copy files to skills directory
2. Register in the skill registry
3. Verify installation

## Parameters

None
        """.trimIndent())

        // Create optional directories
        val scriptsDir = skillDir.resolve("scripts")
        Files.createDirectories(scriptsDir)

        val referencesDir = skillDir.resolve("references")
        Files.createDirectories(referencesDir)
        Files.writeString(referencesDir.resolve("README.md"), "# References\nTest references.")

        val assetsDir = skillDir.resolve("assets")
        Files.createDirectories(assetsDir)

        return skillDir
    }
}
