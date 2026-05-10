package ai.platon.pulsar.agentic.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for SkillDefinitionLoader.
 */
class SkillDefinitionLoaderTest {

    @Test
    fun shouldLoadSkillDefinitionsFromResources() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")

        assertNotNull(definitions)
        assertTrue(definitions.isNotEmpty(), "Should load at least one skill definition")

        // Verify expected skills are loaded
        val skillIds = definitions.map { it.skillId }.toSet()
        assertTrue(skillIds.contains("web-scraping"), "Should load web-scraping skill")
        assertTrue(skillIds.contains("form-filling"), "Should load form-filling skill")
        assertTrue(skillIds.contains("data-validation"), "Should load data-validation skill")
        assertTrue(skillIds.contains("weather"), "Should load weather skill")
    }

    @Test
    fun shouldParseWebScrapingSkillMetadataCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill, "Web scraping skill should be found")
        webScrapingSkill?.let {
            assertEquals("web-scraping", it.skillId)
            assertEquals("web-scraping", it.name)
            assertEquals("1.0.0", it.version)
            assertEquals("Browser4", it.author)
            assertTrue(it.description.isNotBlank())
            assertTrue(it.tags.contains("scraping"))
            assertTrue(it.tags.contains("extraction"))
            assertTrue(it.tags.contains("web"))
            assertTrue(it.dependencies.isEmpty())
        }
    }

    @Test
    fun shouldParseFormFillingSkillWithDependencies() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val formFillingSkill = definitions.find { it.skillId == "form-filling" }

        assertNotNull(formFillingSkill, "Form filling skill should be found")
        formFillingSkill?.let {
            assertEquals("form-filling", it.skillId)
            assertEquals("form-filling", it.name)
            assertTrue(it.dependencies.contains("web-scraping"), "Should have web-scraping dependency")
        }
    }

    @Test
    fun shouldParseWeatherSkillMetadataCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val weatherSkill = definitions.find { it.skillId == "weather" }

        assertNotNull(weatherSkill, "Weather skill should be found")
        weatherSkill?.let {
            assertEquals("weather", it.skillId)
            assertEquals("weather", it.name)
            assertEquals("1.0.0", it.version)
            assertEquals("Browser4", it.author)
            assertTrue(it.description.contains("7-day forecast"))
            assertTrue(it.tags.contains("weather"))
            assertTrue(it.tags.contains("forecast"))
            assertTrue(it.allowedTools.contains("python"))
            assertTrue(it.dependencies.isEmpty())

            val locationParam = it.parameters["location"]
            assertNotNull(locationParam, "Should have location parameter")
            assertEquals("String", locationParam?.type)
            assertTrue(locationParam?.required == true)

            val unitsParam = it.parameters["units"]
            assertNotNull(unitsParam, "Should have units parameter")
            assertEquals("metric", unitsParam?.defaultValue)
            assertFalse(unitsParam?.required == true)
        }
    }

    @Test
    fun shouldParseSkillParametersCorrectly() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            assertTrue(it.parameters.isNotEmpty(), "Should have parameters defined")

            val urlParam = it.parameters["url"]
            assertNotNull(urlParam, "Should have url parameter")
            urlParam?.let { param ->
                assertEquals("String", param.type)
                assertTrue(param.required)
                assertEquals("-", param.defaultValue)
            }

            val selectorParam = it.parameters["selector"]
            assertNotNull(selectorParam, "Should have selector parameter")
            selectorParam?.let { param ->
                assertEquals("String", param.type)
                assertTrue(param.required)
            }

            val attributesParam = it.parameters["attributes"]
            assertNotNull(attributesParam, "Should have attributes parameter")
            attributesParam?.let { param ->
                assertEquals("List<String>", param.type)
                assertFalse(param.required)
            }
        }
    }

    @Test
    fun shouldDetectOptionalDirectories() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            assertNotNull(it.scriptsPath, "Should have scripts directory")
            assertNotNull(it.referencesPath, "Should have references directory")
            assertNotNull(it.assetsPath, "Should have assets directory")
        }
    }

    @Test
    fun shouldGetSkillScripts() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            val scripts = loader.getSkillScripts(it)
            assertTrue(scripts.isNotEmpty(), "Should have at least one script")
            assertTrue(scripts.any { it.fileName.toString().contains("example-usage") })
        }
    }

    @Test
    fun shouldGetSkillReferences() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            val references = loader.getSkillReferences(it)
            assertTrue(references.isNotEmpty(), "Should have at least one reference document")
            assertTrue(references.any { it.fileName.toString().contains("developer-guide") })
        }
    }

    @Test
    fun shouldGetSkillAssets() {
        val loader = SkillDefinitionLoader()

        val definitions = loader.loadFromResources("skills")
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            val assets = loader.getSkillAssets(it)
            assertTrue(assets.isNotEmpty(), "Should have at least one asset")
            assertTrue(assets.any { it.fileName.toString().contains("config.json") })
        }
    }

    @Test
    fun shouldLoadFromCustomDirectory(@TempDir tempDir: Path) {
        // Create a test skill directory
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
              - web-scraping
              - data-validation
            allowed-tools: Bash(git:*) Read
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

        // Create optional directories
        Files.createDirectories(skillDir.resolve("scripts"))
        Files.createDirectories(skillDir.resolve("references"))
        Files.createDirectories(skillDir.resolve("assets"))

        // Load from custom directory
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
        assertTrue(testSkill.dependencies.contains("web-scraping"))
        assertTrue(testSkill.dependencies.contains("data-validation"))
        assertEquals("Apache-2.0", testSkill.license)
        assertEquals("Designed for unit tests", testSkill.compatibility)
        assertEquals("Test Author", testSkill.metadata["author"])
        assertEquals("2.0.0", testSkill.metadata["version"])
        assertTrue(testSkill.allowedTools.contains("Bash(git:*)"))
        assertTrue(testSkill.allowedTools.contains("Read"))

        assertEquals(2, testSkill.parameters.size)
        assertTrue(testSkill.parameters.containsKey("testParam"))
        assertTrue(testSkill.parameters.containsKey("optionalParam"))
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
        val webScrapingSkill = definitions.find { it.skillId == "web-scraping" }

        assertNotNull(webScrapingSkill)
        webScrapingSkill?.let {
            assertTrue(it.dependencies.isEmpty(), "Web scraping skill should have no dependencies")
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
