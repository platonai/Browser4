package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.coding.ArtifactValidator
import ai.platon.pulsar.coding.Severity
import ai.platon.pulsar.coding.ValidationIssue
import ai.platon.pulsar.coding.ValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class SkillDraftGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private val validSkillMd = """
        ---
        name: refactor-auth
        description: Generalized steps for refactoring an authentication module.
        allowed-tools: coding.read coding.edit coding.shell
        version: "1.0.0"
        generated: true
        ---
        # Refactor Auth Module
        ## Overview
        Reusable steps to refactor authentication code.
        ## When to Use
        When the auth module needs restructuring.
        ## How It Works
        1. Read the current auth implementation.
        2. Extract the common interface.
        3. Edit call sites to use the new interface.
        ## Examples
        - Applied to a login flow consolidation.
    """.trimIndent()

    private fun ctx(prompt: String = "refactor the auth module", tools: Map<String, Long> = mapOf("coding.read" to 3L, "coding.edit" to 2L, "coding.shell" to 1L)) =
        TaskSummaryContext(
            taskId = "t-1", prompt = prompt, result = "Done", workspace = null,
            rounds = 5, toolCalls = 6, mutations = 2,
            toolCounts = tools, toolSequence = emptyList(),
            verifyVerdict = "passed", filesChanged = listOf("src/Auth.kt"),
            inputTokens = 100, outputTokens = 50, durationMs = 12000,
        )

    private fun generator(llm: suspend (String, String) -> String?) =
        SkillDraftGenerator(tempDir, llm = llm)

    @Test
    @DisplayName("TR-4.1 generated SKILL.md passes validateSkill (no ERROR) and is written to disk")
    fun generatesValidSkill() = runBlocking {
        val gen = generator { _, _ -> validSkillMd }
        val result = gen.generate(ctx())

        assertTrue(result.validation.valid, "issues: ${result.validation.issues}")
        assertTrue(result.llmUsed)
        val skillFile = result.dir.resolve("SKILL.md")
        assertTrue(Files.exists(skillFile))
        assertTrue(Files.exists(result.dir.resolve("context.json")))
        // independent re-validation
        val recheck = ArtifactValidator.validateSkill(Files.readString(skillFile), "${result.skillId}/SKILL.md")
        assertTrue(recheck.valid, "recheck issues: ${recheck.issues}")
    }

    @Test
    @DisplayName("TR-4.2 allowed-tools in output contains only real tools from the run (no hallucination)")
    fun allowedToolsOnlyReal() = runBlocking {
        // LLM hallucinates "coding.frobnicate" which is not a real tool used
        val withHalluc = validSkillMd.replace(
            "allowed-tools: coding.read coding.edit coding.shell",
            "allowed-tools: coding.read coding.edit coding.shell coding.frobnicate",
        )
        val gen = generator { _, _ -> withHalluc }
        val result = gen.generate(ctx(tools = mapOf("coding.read" to 3L, "coding.edit" to 2L, "coding.shell" to 1L)))

        val content = Files.readString(result.dir.resolve("SKILL.md"))
        val allowedLine = content.lineSequence().first { it.startsWith("allowed-tools:") }
        val listed = allowedLine.removePrefix("allowed-tools:").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val known = setOf("coding.read", "coding.edit", "coding.shell")
        listed.forEach { assertTrue(it in known, "hallucinated tool leaked: $it") }
        assertFalse(listed.contains("coding.frobnicate"))
    }

    @Test
    @DisplayName("TR-4.3 a leaked secret in LLM output is redacted in the written SKILL.md")
    fun redactsSecrets() = runBlocking {
        // SecretRedactor scans JSON-shaped "key": value pairs and CLI --flag values
        val leaky = validSkillMd + "\n\nNote: config had \"password\": \"hunter2\" and flag --token sk-1234.\n"
        val gen = generator { _, _ -> leaky }
        val result = gen.generate(ctx())

        val content = Files.readString(result.dir.resolve("SKILL.md"))
        assertFalse(content.contains("hunter2"), "plaintext password leaked")
        assertFalse(content.contains("sk-1234"), "plaintext token leaked")
        assertTrue(content.contains("***"), "redaction placeholder missing")
    }

    @Test
    @DisplayName("TR-4.4 LLM unavailable (null) degrades to context.json only, no SKILL.md, no throw")
    fun degradesWhenLlmUnavailable() = runBlocking {
        val gen = generator { _, _ -> null }
        val result = gen.generate(ctx())

        assertFalse(result.llmUsed)
        assertNull(result.skillMd)
        // no ERROR-level issue (degradation is INFO)
        assertFalse(result.validation.issues.any { it.severity == ai.platon.pulsar.coding.Severity.ERROR })
        assertTrue(Files.exists(result.dir.resolve("context.json")))
        assertFalse(Files.exists(result.dir.resolve("SKILL.md")))
    }

    @Test
    @DisplayName("missing/invalid frontmatter name is derived from the prompt (kebab-case)")
    fun derivesNameWhenMissing() = runBlocking {
        val noName = validSkillMd.replace(Regex("""^name:.*\n""", setOf(RegexOption.MULTILINE)), "")
        val gen = generator { _, _ -> noName }
        val result = gen.generate(ctx(prompt = "refactor the auth module"))

        assertEquals("refactor-auth-module", result.skillId)
        val content = Files.readString(result.dir.resolve("SKILL.md"))
        assertTrue(content.contains("name: refactor-auth-module"))
        assertTrue(result.validation.valid)
    }

    @Test
    @DisplayName("invalid (non-kebab) name is replaced with a derived one")
    fun replacesInvalidName() = runBlocking {
        val badName = validSkillMd.replace("name: refactor-auth", "name: Refactor Auth_Module")
        val gen = generator { _, _ -> badName }
        val result = gen.generate(ctx(prompt = "refactor the auth module"))

        assertEquals("refactor-auth-module", result.skillId)
        val content = Files.readString(result.dir.resolve("SKILL.md"))
        // the whole line: a leftover token (`name: refactor-auth-module Auth_Module`) is not a loadable name
        assertEquals("name: refactor-auth-module", content.lineSequence().first { it.startsWith("name:") })
        assertTrue(result.validation.valid, "issues: ${result.validation.issues}")
    }

    @Test
    @DisplayName("FR-7 frontmatter carries provenance markers generated/source-task-id/generated-at")
    fun injectsProvenanceMarkers() = runBlocking {
        val gen = generator { _, _ -> validSkillMd }
        val result = gen.generate(ctx())
        val content = Files.readString(result.dir.resolve("SKILL.md"))
        assertTrue(content.contains("generated: true"), "missing generated marker")
        assertTrue(content.contains("source-task-id: t-1"), "missing source-task-id marker")
        assertTrue(content.contains("generated-at:"), "missing generated-at marker")
        // injection must not break validation
        assertTrue(result.validation.valid, "provenance injection broke validation: ${result.validation.issues}")
    }

    @Test
    @DisplayName("TR-4.1 the draft directory name equals the skill id, so promote/install can parse it")
    fun draftDirectoryNameEqualsSkillId() = runBlocking {
        val gen = generator { _, _ -> validSkillMd }
        val result = gen.generate(ctx())
        assertEquals(result.skillId, result.dir.fileName.toString())
    }

    @Test
    @DisplayName("TR-4.5 a draft that fails validation does not expose SKILL.md")
    fun invalidDraftDoesNotExposeSkillMd() = runBlocking {
        val gen = SkillDraftGenerator(
            tempDir,
            llm = { _, _ -> validSkillMd },
            validator = { _, _ ->
                ValidationResult.of(listOf(ValidationIssue(Severity.ERROR, "rejected by test validator")))
            },
        )
        val result = gen.generate(ctx())
        assertFalse(result.validation.valid)
        assertFalse(
            Files.exists(result.dir.resolve("SKILL.md")),
            "an invalid draft must not be listed or promotable as a skill",
        )
        assertTrue(Files.exists(result.dir.resolve("context.json")), "context.json is still useful for triage")
    }

    @Test
    @DisplayName("a draft the regex validator accepts but the real loader cannot parse is rejected, not written")
    fun unparseableFrontmatterIsRejectedByRealLoader() = runBlocking {
        // An unquoted `: ` inside the description is invalid YAML: promote / auto install would fail on it.
        val broken = validSkillMd.replace(
            "description: Generalized steps for refactoring an authentication module.",
            "description: Use when: the auth module needs restructuring",
        )
        val gen = generator { _, _ -> broken }
        val result = gen.generate(ctx())

        assertFalse(result.validation.valid, "the real loader must be the final gate")
        assertFalse(Files.exists(result.dir.resolve("SKILL.md")))
        assertTrue(
            result.validation.issues.any { it.severity == Severity.ERROR && it.message.contains("SkillDefinitionLoader") },
            "issues: ${result.validation.issues}",
        )
    }

    @Test
    @DisplayName("a name longer than the loader's 64-character limit is cut to fit")
    fun longNameIsCutToLoaderLimit() = runBlocking {
        val longName = "a" + "-bc".repeat(24)
        val gen = generator { _, _ -> validSkillMd.replace("name: refactor-auth", "name: $longName") }
        val result = gen.generate(ctx())

        assertTrue(result.validation.valid, "issues: ${result.validation.issues}")
        assertEquals(64, result.skillId.length)
        assertEquals(result.skillId, result.dir.fileName.toString())
        assertTrue(Files.exists(result.dir.resolve("SKILL.md")))
    }

    @Test
    @DisplayName("a name taken by a registered, deployed or already drafted skill gets a suffix instead of overwriting it")
    fun takenNameGetsSuffix() = runBlocking {
        val taken = mutableSetOf<String>()
        val gen = SkillDraftGenerator(tempDir, llm = { _, _ -> validSkillMd }, isNameTaken = { it in taken })

        // taken by a registered / deployed skill
        taken += "refactor-auth"
        assertEquals("refactor-auth-2", gen.generate(ctx()).skillId)

        // nothing taken and no draft yet: the name itself, and the draft lands on disk
        taken.clear()
        val plain = gen.generate(ctx())
        assertEquals("refactor-auth", plain.skillId)
        assertTrue(Files.exists(plain.dir.resolve("SKILL.md")))

        // a draft already carries the name (and so does the first suffixed one)
        assertEquals("refactor-auth-3", gen.generate(ctx()).skillId)
    }

    @Test
    @DisplayName("renderContext gives the LLM argument previews and the failed -> succeeded pairs, redacted")
    fun renderContextCarriesCorrections() {
        val failed = """{"selector":"#price"}"""
        val succeeded = """{"selector":".price"}"""
        val context = ctx().copy(
            toolSequence = listOf(
                ToolCallRecord("tab.click", failed, false, 120L, "not found"),
                ToolCallRecord("tab.click", succeeded, true, 90L, "ok"),
                ToolCallRecord("fill", """{"selector":"#password","text":"hunter2"}""", true, 50L, "ok"),
            ),
            corrections = listOf(Correction("tab.click", failed, succeeded)),
        )
        val text = generator { _, _ -> validSkillMd }.renderContext(context)

        assertTrue(text.contains("failed with {\"selector\":\"#price\"} -> succeeded with {\"selector\":\".price\"}"))
        assertFalse(text.contains("hunter2"), "a value typed into a credential field must not reach the LLM context")
    }

    @Test
    @DisplayName("free text is redacted before it is truncated")
    fun redactsBeforeTruncating() {
        // The result is cut at 2000 chars: a secret straddling that line must still be redacted.
        val result = "x".repeat(1985) + " password: hunter2 the end"
        val text = generator { _, _ -> validSkillMd }.renderContext(ctx().copy(result = result))

        assertFalse(text.contains("hunte"), "truncating first would leave half of the secret")
        assertTrue(text.contains("password: ***"))
    }
}
