package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class SkillAppenderTest {

    @TempDir
    lateinit var tempDir: Path

    private fun ctx(
        taskId: String = "task-1",
        prompt: String = "refactor the auth module",
        tools: Map<String, Long> = mapOf("coding.read" to 3L, "coding.edit" to 2L),
    ) = TaskSummaryContext(
        taskId = taskId, prompt = prompt, result = null, workspace = null,
        rounds = 3, toolCalls = 5, mutations = 2, toolCounts = tools,
        toolSequence = emptyList(), verifyVerdict = null, filesChanged = emptyList(),
        inputTokens = 100, outputTokens = 200, durationMs = 1500L,
    )

    private fun skillMd(version: String = "1.0.0", withExamples: Boolean = true): String {
        val examples = if (withExamples) "\n            ## Examples\n            - existing example\n" else "\n"
        return """
            ---
            name: refactor-auth
            description: Generalized steps for refactoring an authentication module.
            version: "$version"
            ---
            # Refactor Auth
            ## Overview
            Reusable steps.$examples
        """.trimIndent()
    }

    private fun writeDraft(skillId: String = "refactor-auth", suffix: String = "100", content: String = skillMd()): Path {
        val dir = tempDir.resolve("$skillId-$suffix")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("SKILL.md"), content)
        return dir
    }

    @Test
    @DisplayName("appends an Example entry and bumps the patch version")
    fun appendsExampleAndBumpsVersion() {
        val dir = writeDraft()
        val appender = SkillAppender(tempDir, tempDir.resolve("skills-none"))
        val result = appender.append("refactor-auth", "task-1", ctx())
        assertTrue(result.appended, "got $result")
        val content = Files.readString(dir.resolve("SKILL.md"))
        assertTrue(content.contains("<!-- task-id: task-1 -->"), "marker must be present")
        assertTrue(content.contains("refactor the auth module"), "prompt summary must be present")
        assertTrue(content.contains("coding.read(3)"), "tool counts must be present")
        assertTrue(content.contains("version: \"1.0.1\""), "patch version must bump")
        assertFalse(content.contains("version: \"1.0.0\""), "old version must be replaced")
    }

    @Test
    @DisplayName("TR-6.2 the same taskId does not produce a duplicate Example")
    fun idempotentSameTaskIdNotDuplicated() {
        val dir = writeDraft()
        val appender = SkillAppender(tempDir, tempDir.resolve("skills-none"))
        val first = appender.append("refactor-auth", "task-1", ctx())
        assertTrue(first.appended)
        val second = appender.append("refactor-auth", "task-1", ctx())
        assertFalse(second.appended, "second append for the same taskId must be a no-op")
        assertTrue(second.reason.contains("already appended"), "reason=${second.reason}")
        val content = Files.readString(dir.resolve("SKILL.md"))
        val occurrences = content.split("<!-- task-id: task-1 -->").size - 1
        assertEquals(1, occurrences, "marker must appear exactly once")
    }

    @Test
    @DisplayName("different taskId appends another Example to the same skill")
    fun differentTaskIdAppendsAnother() {
        val dir = writeDraft()
        val appender = SkillAppender(tempDir, tempDir.resolve("skills-none"))
        appender.append("refactor-auth", "task-1", ctx(taskId = "task-1"))
        appender.append("refactor-auth", "task-2", ctx(taskId = "task-2", prompt = "refactor auth again"))
        val content = Files.readString(dir.resolve("SKILL.md"))
        assertTrue(content.contains("<!-- task-id: task-1 -->"))
        assertTrue(content.contains("<!-- task-id: task-2 -->"))
        assertTrue(content.contains("version: \"1.0.2\""), "two appends must bump patch twice")
    }

    @Test
    @DisplayName("returns not-found and does not throw when no target skill exists")
    fun returnsNotFoundWhenTargetAbsent() {
        val empty = tempDir.resolve("empty")
        Files.createDirectories(empty)
        val appender = SkillAppender(empty, empty.resolve("no-skills-dir"))
        val result = appender.append("does-not-exist", "task-1", ctx())
        assertFalse(result.appended)
        assertTrue(result.reason.contains("not found"), "reason=${result.reason}")
    }

    @Test
    @DisplayName("creates an Examples section when the skill lacks one")
    fun createsExamplesSectionWhenAbsent() {
        val dir = writeDraft(content = skillMd(withExamples = false))
        val appender = SkillAppender(tempDir, tempDir.resolve("skills-none"))
        val result = appender.append("refactor-auth", "task-1", ctx())
        assertTrue(result.appended, "got $result")
        val content = Files.readString(dir.resolve("SKILL.md"))
        assertTrue(content.contains("## Examples"))
        assertTrue(content.contains("<!-- task-id: task-1 -->"))
    }
}
