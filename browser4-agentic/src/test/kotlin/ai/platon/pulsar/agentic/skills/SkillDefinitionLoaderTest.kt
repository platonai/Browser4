package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for SkillDefinitionLoader.
 *
 * `browser4-cli` is the only skill bundled in `src/main/resources/skills`; the former demo
 * skills (`web-scraping`, `form-filling`, `data-validation`, `pdf`, `mcp-builder`, `weather`)
 * were removed. Tests that need a full SKILL.md shape (parameters, dependencies, optional
 * folders) build one in a temp directory instead of relying on a bundled skill, so the loader
 * contract keeps its coverage without shipping demo content.
 */
class SkillDefinitionLoaderTest {

    private val removedDemoSkillIds = listOf(
        "web-scraping", "form-filling", "data-validation", "mcp-builder", "pdf", "weather"
    )

    @Test
    fun shouldLoadBundledSkillFromResources() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")

        assertNotNull(definitions)
        assertTrue(definitions.isNotEmpty(), "Should load at least one skill definition")

        val skillIds = definitions.map { it.skillId }.toSet()
        assertTrue(skillIds.contains("browser4-cli"), "Should load the bundled browser4-cli skill")
        removedDemoSkillIds.forEach { removed ->
            assertFalse(skillIds.contains(removed), "Demo skill '$removed' must not be bundled any more")
        }
    }

    @Test
    fun shouldParseBundledCliSkillMetadataCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val cliSkill = definitions.find { it.skillId == "browser4-cli" }

        assertNotNull(cliSkill, "browser4-cli skill should be found")
        cliSkill?.let {
            assertEquals("browser4-cli", it.skillId)
            assertEquals("browser4-cli", it.name)
            assertEquals("1.0.0", it.version)
            assertTrue(it.description.isNotBlank())
            assertTrue(it.tags.contains("browser"))
            assertTrue(it.tags.contains("automation"))
            assertTrue(it.dependencies.isEmpty())
        }
    }

    @Test
    fun shouldDetectBundledCliReferencesDirectory() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val cliSkill = definitions.find { it.skillId == "browser4-cli" }

        assertNotNull(cliSkill)
        cliSkill?.let {
            assertNotNull(it.referencesPath, "browser4-cli ships a references/ directory")

            val references = loader.getSkillReferences(it)
            assertTrue(references.isNotEmpty(), "Should have at least one reference document")
            assertTrue(references.any { ref -> ref.fileName.toString() == "quickstart.md" })
        }
    }

    @Test
    fun shouldParseSkillDefinitionFromDirectory(@TempDir tempDir: Path) {
        val skillDir = tempDir.resolve("test-skill")
        Files.createDirectories(skillDir)

        val skillMd = """
            ---
            name: test-skill
            description: This is a test skill for unit testing.
            license: Apache-2.0
            compatibility: Designed for unit tests
            metadata:
              author: Test Author
              version: "2.0.0"
            tags:
              - test
              - example
            dependencies:
              - base-skill
              - util-skill
            allowed-tools: python
            ---

            # Test Skill

            ## Parameters

            | Parameter | Type | Required | Default | Description |
            |-----------|------|----------|---------|-------------|
            | testParam | String | Yes | - | Test parameter |
            | optionalParam | Boolean | No | false | Optional parameter |

            ## Usage Examples

            ```kotlin
            val result = execute(context, params)
            ```
        """.trimIndent()

        Files.writeString(skillDir.resolve("SKILL.md"), skillMd)

        // Create optional directories with one file each
        val scripts = Files.createDirectories(skillDir.resolve("scripts"))
        Files.writeString(scripts.resolve("run.py"), "print('hello')\n")
        val references = Files.createDirectories(skillDir.resolve("references"))
        Files.writeString(references.resolve("developer-guide.md"), "# Guide\n")
        val assets = Files.createDirectories(skillDir.resolve("assets"))
        Files.writeString(assets.resolve("config.json"), "{}\n")

        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromDirectory(tempDir)

        assertEquals(1, definitions.size, "Should load exactly one skill")

        val testSkill = definitions[0]
        assertEquals("test-skill", testSkill.skillId)
        assertEquals("test-skill", testSkill.name)
        assertEquals("2.0.0", testSkill.version)
        assertEquals("Test Author", testSkill.author)
        assertTrue(testSkill.tags.contains("test"))
        assertTrue(testSkill.tags.contains("example"))
        assertEquals(2, testSkill.dependencies.size)
        assertTrue(testSkill.dependencies.contains("base-skill"))
        assertTrue(testSkill.dependencies.contains("util-skill"))
        assertEquals("Apache-2.0", testSkill.license)
        assertEquals("Designed for unit tests", testSkill.compatibility)
        assertEquals("Test Author", testSkill.metadata["author"])
        assertEquals("2.0.0", testSkill.metadata["version"])
        assertTrue(testSkill.allowedTools.contains("python"))

        assertEquals(2, testSkill.parameters.size)
        val testParam = testSkill.parameters["testParam"]
        assertNotNull(testParam, "Should have testParam parameter")
        assertEquals("String", testParam?.type)
        assertTrue(testParam?.required == true)
        assertEquals("-", testParam?.defaultValue)

        val optionalParam = testSkill.parameters["optionalParam"]
        assertNotNull(optionalParam, "Should have optionalParam parameter")
        assertEquals("Boolean", optionalParam?.type)
        assertFalse(optionalParam?.required == true)
        assertEquals("false", optionalParam?.defaultValue)

        // Optional directory detection
        assertNotNull(testSkill.scriptsPath, "Should have scripts directory")
        assertNotNull(testSkill.referencesPath, "Should have references directory")
        assertNotNull(testSkill.assetsPath, "Should have assets directory")

        val scriptFiles = loader.getSkillScripts(testSkill)
        assertTrue(scriptFiles.any { it.fileName.toString() == "run.py" })

        val referenceFiles = loader.getSkillReferences(testSkill)
        assertTrue(referenceFiles.any { it.fileName.toString() == "developer-guide.md" })

        val assetFiles = loader.getSkillAssets(testSkill)
        assertTrue(assetFiles.any { it.fileName.toString() == "config.json" })
    }

    @Test
    fun shouldHandleMissingSkillMdFile(@TempDir tempDir: Path) {
        val skillDir = tempDir.resolve("invalid-skill")
        Files.createDirectories(skillDir)
        // Don't create SKILL.md

        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromDirectory(tempDir)

        assertTrue(definitions.isEmpty(), "Should not load skills without SKILL.md")
    }

    @Test
    fun shouldHandleEmptySkillsDirectory(@TempDir tempDir: Path) {
        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromDirectory(tempDir)

        assertTrue(definitions.isEmpty(), "Should return empty list for empty directory")
    }

    @Test
    fun shouldParseTagsCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")

        definitions.forEach { definition ->
            assertNotNull(definition.tags)
            assertTrue(definition.tags.isNotEmpty(), "Skill ${definition.skillId} should have tags")

            // Tags should not contain backticks or quotes
            definition.tags.forEach { tag ->
                assertFalse(tag.contains("`"), "Tag should not contain backticks: $tag")
                assertFalse(tag.contains("\""), "Tag should not contain quotes: $tag")
            }
        }
    }

    @Test
    fun shouldParseDescriptionCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")

        definitions.forEach { definition ->
            assertNotNull(definition.description)
            assertTrue(definition.description.isNotBlank(), "Skill ${definition.skillId} should have description")
            assertFalse(definition.description.startsWith("#"), "Description should not start with #")
        }
    }

    @Test
    fun shouldHandleSkillsWithNoDependencies() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val cliSkill = definitions.find { it.skillId == "browser4-cli" }

        assertNotNull(cliSkill)
        cliSkill?.let {
            assertTrue(it.dependencies.isEmpty(), "browser4-cli should have no dependencies")
        }
    }

    @Test
    fun shouldParseVersionInSemverFormat() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")

        definitions.forEach { definition ->
            assertTrue(definition.version.matches(Regex("""\d+\.\d+\.\d+""")),
                "Version should be in semver format: ${definition.version}")
        }
    }

    @Test
    fun shouldReturnEmptyWhenResourcePathNotFound() {
        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromResources("skills-not-exist-__ut__")
        assertNotNull(definitions)
        assertTrue(definitions.isEmpty())
    }

    @Test
    fun shouldSkipMalformedSkillMd(@TempDir tempDir: Path) {
        val badSkillDir = tempDir.resolve("bad-skill")
        Files.createDirectories(badSkillDir)

        // Missing required Skill ID / Name in markdown format -> parseFromMarkdown require(...) will throw
        Files.writeString(
            badSkillDir.resolve("SKILL.md"),
            """
                # Bad Skill

                ## Metadata
                - **Version**: 1.0.0

                ## Description
                This skill is malformed.
            """.trimIndent()
        )

        val loader = SkillDefinitionLoader()
        val definitions = loader.loadFromDirectory(tempDir)

        assertTrue(definitions.isEmpty(), "Malformed SKILL.md should be skipped by loadFromDirectory")
    }

    @Test
    fun shouldSupportClasspathResourcePathVariants() {
        val loader = SkillDefinitionLoader()

        val defs1 = loader.loadFromResources("skills")
        val defs2 = loader.loadFromResources("/skills")
        val defs3 = loader.loadFromResources("classpath:skills")

        assertTrue(defs1.isNotEmpty())
        assertEquals(defs1.map { it.skillId }.toSet(), defs2.map { it.skillId }.toSet())
        assertEquals(defs1.map { it.skillId }.toSet(), defs3.map { it.skillId }.toSet())
    }
}
