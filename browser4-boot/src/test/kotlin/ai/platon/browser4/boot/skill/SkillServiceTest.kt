package ai.platon.browser4.boot.skill

import ai.platon.pulsar.agentic.skills.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class SkillServiceTest {

    // ---- Helpers ----

    private fun createTempSkillsDir(): Path {
        return Files.createTempDirectory("browser4-test-skills-")
    }

    private fun createSkillDir(
        parentDir: Path,
        skillId: String,
        name: String = skillId,
        version: String = "1.0.0",
        description: String = "A test skill",
    ): Path {
        val skillDir = parentDir.resolve(skillId)
        Files.createDirectories(skillDir)

        val skillMd = """
            ---
            name: $name
            description: $description
            version: $version
            ---
            ## Description
            This is a test skill for unit tests.
        """.trimIndent()

        Files.writeString(skillDir.resolve("SKILL.md"), skillMd)
        // Create a scripts/ directory so that SkillRegistry.activateSkill
        // can resolve the path back to the skill dir and read SKILL.md
        Files.createDirectories(skillDir.resolve("scripts"))
        return skillDir
    }

    private fun mockAppContext(): ApplicationContext {
        return Mockito.mock(ApplicationContext::class.java)
    }

    @BeforeEach
    fun setUp() = runBlocking {
        // Clear the singleton registry between tests to avoid cross-test pollution
        SkillRegistry.instance.clear(SkillContext(sessionId = "cleanup"))
    }

    // ---- listSkills() ----

    @Test
    @DisplayName("listSkills returns empty list when no skills registered")
    fun testListSkillsReturnsEmptyWhenNoneRegistered() {
        val registry = SkillRegistry.instance
        val service = SkillService(
            mockAppContext(),
            createTempSkillsDir(),
            registry = registry,
        )

        val result = service.listSkills()
        assertTrue(result.isEmpty(), "Expected empty list but got ${result.size} skills")
    }

    @Test
    @DisplayName("listSkills returns summaries for registered skills")
    fun testListSkillsReturnsSummariesForRegisteredSkills() = runBlocking {
        val registry = SkillRegistry.instance
        val context = SkillContext(sessionId = "test")
        val skillDir = createSkillDir(createTempSkillsDir(), "test-skill")

        // Load a skill via definition loader
        val definitionLoader = SkillDefinitionLoader()
        val definitions = definitionLoader.loadFromDirectory(skillDir.parent)
        val definition = definitions.first { it.skillId == "test-skill" }
        val skill = DefinitionBackedSkill(
            definition,
            DefinitionBackedSkill.Origin.FileSystem(skillDir)
        )

        registry.register(skill, context)

        val service = SkillService(
            mockAppContext(),
            skillDir.parent,
            registry = registry,
            definitionLoader = definitionLoader,
        )

        val result = service.listSkills()
        assertEquals(1, result.size, "Expected 1 skill but got ${result.size}: ${result.map { it.id }}")
        assertEquals("test-skill", result[0].id)
        assertEquals("test-skill", result[0].name)
    }

    // ---- getSkill() ----

    @Test
    @DisplayName("getSkill returns null when skill not found")
    fun testGetSkillReturnsNullWhenNotFound() {
        val service = SkillService(mockAppContext(), createTempSkillsDir())
        assertNull(service.getSkill("nonexistent"))
    }

    @Test
    @DisplayName("getSkill returns detail for registered skill")
    fun testGetSkillReturnsDetailForRegisteredSkill() = runBlocking {
        val registry = SkillRegistry.instance
        val context = SkillContext(sessionId = "test")
        val skillDir = createSkillDir(createTempSkillsDir(), "my-skill", description = "Does something useful")

        val definitionLoader = SkillDefinitionLoader()
        val definitions = definitionLoader.loadFromDirectory(skillDir.parent)
        val definition = definitions.first { it.skillId == "my-skill" }
        val skill = DefinitionBackedSkill(
            definition,
            DefinitionBackedSkill.Origin.FileSystem(skillDir)
        )

        registry.register(skill, context)

        val service = SkillService(
            mockAppContext(),
            skillDir.parent,
            registry = registry,
        )

        val detail = service.getSkill("my-skill")
        assertNotNull(detail)
        assertEquals("my-skill", detail!!.id)
        assertEquals("my-skill", detail.name)
        assertEquals("1.0.0", detail.version)
        assertEquals("Does something useful", detail.description)
        // SKILL.md should be readable from the filesystem origin
        assertTrue(detail.skillMd.isNotBlank(), "SKILL.md content should be populated, got: '${detail.skillMd}'")
    }

    // ---- installSkill() ----

    @Test
    @DisplayName("installSkill throws IllegalArgumentException on non-directory source")
    fun testInstallSkillThrowsOnNonDirectorySource() = runBlocking {
        val service = SkillService(mockAppContext(), createTempSkillsDir())

        try {
            service.installSkill(Path.of("/no/such/dir"))
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not a directory"))
        }
    }

    @Test
    @DisplayName("installSkill throws IllegalArgumentException when SKILL.md missing")
    fun testInstallSkillThrowsWhenSkillMdMissing() = runBlocking {
        val emptyDir = Files.createTempDirectory("empty-skill-")
        val service = SkillService(mockAppContext(), createTempSkillsDir())

        try {
            service.installSkill(emptyDir)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SKILL.md not found"))
        }
    }

    // ---- uninstallSkill() ----

    @Test
    @DisplayName("uninstallSkill throws IllegalArgumentException for unregistered skill")
    fun testUninstallSkillThrowsForUnregisteredSkill() = runBlocking {
        val service = SkillService(mockAppContext(), createTempSkillsDir())

        try {
            service.uninstallSkill("nonexistent")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not registered"))
        }
    }

    // ---- reloadSkill() ----

    @Test
    @DisplayName("reloadSkill throws IllegalArgumentException when skill not found")
    fun testReloadSkillThrowsWhenSkillNotFound() = runBlocking {
        val service = SkillService(mockAppContext(), createTempSkillsDir())

        try {
            service.reloadSkill("nonexistent")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not registered"))
        }
    }
}
