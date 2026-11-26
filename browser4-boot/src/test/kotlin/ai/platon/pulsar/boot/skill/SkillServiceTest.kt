package ai.platon.pulsar.boot.skill

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

    // ---- listDrafts() / promoteDraft() (auto-generated drafts) ----

    /** Older drafts were named `<skillId>-<epochMillis>`; promote must still install them. */
    private fun createLegacyDraft(parent: Path, skillId: String, ts: Long, taskId: String): Path {
        val legacy = parent.resolve("$skillId-$ts")
        Files.move(createDraft(parent, skillId, taskId), legacy)
        return legacy
    }

    private fun createDraft(parent: Path, skillId: String, taskId: String): Path {
        // Production naming: the draft directory name equals the skill id, because
        // SkillInstaller parses a draft with `require(name == directoryName)`.
        val dir = parent.resolve(skillId)
        Files.createDirectories(dir)
        val md = """
            ---
            name: $skillId
            description: Drafted from task $taskId
            version: "1.0.0"
            ---
            # $skillId
            ## Overview
            Auto-generated draft.
        """.trimIndent()
        Files.writeString(dir.resolve("SKILL.md"), md)
        Files.writeString(
            dir.resolve("context.json"),
            "{\"taskId\":\"$taskId\",\"prompt\":\"do something\",\"rounds\":3}",
        )
        return dir
    }

    @Test
    @DisplayName("listDrafts returns auto-generated drafts with parsed metadata")
    fun testListDraftsReturnsParsedDrafts() {
        val registry = SkillRegistry.instance
        val draftsDir = Files.createTempDirectory("browser4-test-drafts-")
        createDraft(draftsDir, "refactor-auth", "task-abc")
        val service = SkillService(
            mockAppContext(), createTempSkillsDir(), registry = registry, draftsDir = draftsDir,
        )

        val drafts = service.listDrafts()
        assertEquals(1, drafts.size, "Expected 1 draft but got ${drafts.size}")
        val d = drafts[0]
        assertEquals("refactor-auth", d.id)
        assertEquals("refactor-auth", d.skillId)
        assertTrue(d.generatedAt != null, "generatedAt should fall back to the directory mtime")
        assertEquals("task-abc", d.sourceTaskId)
        assertEquals("1.0.0", d.version)
        assertTrue(d.description?.contains("task-abc") == true, "description=${d.description}")
        assertFalse(d.registered, "draft should not be registered before promote")
    }

    @Test
    @DisplayName("listDrafts returns empty list when drafts dir absent")
    fun testListDraftsReturnsEmptyWhenDirAbsent() {
        val service = SkillService(
            mockAppContext(), createTempSkillsDir(),
            draftsDir = createTempSkillsDir().resolve("does-not-exist"),
        )
        assertTrue(service.listDrafts().isEmpty())
    }

    @Test
    @DisplayName("promoteDraft installs a draft and flags it registered")
    fun testPromoteDraftInstallsDraft() {
        runBlocking {
            val registry = SkillRegistry.instance
            val skillsDir = createTempSkillsDir()
            val draftsDir = Files.createTempDirectory("browser4-test-drafts-")
            createDraft(draftsDir, "promote-me", "task-xyz")
            val service = SkillService(
                mockAppContext(), skillsDir, registry = registry, draftsDir = draftsDir,
            )

            val before = service.listDrafts()
            assertFalse(before[0].registered)

            val result = service.promoteDraft("promote-me")
            assertTrue(result.success, "promote failed: ${result.message}")
            assertEquals("promote-me", result.skillId)
            assertTrue(registry.contains("promote-me"), "skill should be registered after promote")

            val after = service.listDrafts()
            assertEquals(1, after.size)
            assertTrue(after[0].registered, "draft should be registered after promote")

            runCatching { service.uninstallSkill("promote-me") }
        }
    }

    @Test
    @DisplayName("promoteDraft also installs a legacy `<skillId>-<epochMillis>` draft")
    fun testPromoteDraftInstallsLegacyNamedDraft() {
        runBlocking {
            val registry = SkillRegistry.instance
            val skillsDir = createTempSkillsDir()
            val draftsDir = Files.createTempDirectory("browser4-test-drafts-legacy-")
            createLegacyDraft(draftsDir, "legacy-skill", 1732456789000L, "task-legacy")
            val service = SkillService(
                mockAppContext(), skillsDir, registry = registry, draftsDir = draftsDir,
            )

            val result = service.promoteDraft("legacy-skill-1732456789000")
            assertTrue(result.success, "legacy promote failed: ${result.message}")
            assertTrue(registry.contains("legacy-skill"), "legacy draft should register as its skill id")

            runCatching { service.uninstallSkill("legacy-skill") }
        }
    }

    @Test
    @DisplayName("promoteDraft throws IllegalArgumentException for missing draft")
    fun testPromoteDraftThrowsForMissingDraft() = runBlocking {
        val service = SkillService(
            mockAppContext(), createTempSkillsDir(),
            draftsDir = Files.createTempDirectory("empty-drafts-"),
        )
        try {
            service.promoteDraft("nope-123")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"), "message=${e.message}")
        }
    }

    @Test
    @DisplayName("promoteDraft rejects path-traversal draft ids")
    fun testPromoteDraftRejectsPathTraversal() = runBlocking {
        val draftsDir = Files.createTempDirectory("drafts-")
        val service = SkillService(mockAppContext(), createTempSkillsDir(), draftsDir = draftsDir)
        try {
            service.promoteDraft("../etc-passwd")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected: draft id escapes the drafts directory
        }
    }
}
