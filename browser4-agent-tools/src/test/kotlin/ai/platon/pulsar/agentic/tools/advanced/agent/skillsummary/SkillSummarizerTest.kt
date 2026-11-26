package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
class SkillSummarizerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var prevIndexFactory: (SkillSummaryConfig) -> SkillFingerprintIndex
    private lateinit var prevGeneratorFactory: (SkillSummaryConfig, (suspend (String, String) -> String?)?) -> SkillDraftGenerator
    private lateinit var prevRegistrarFactory: (SkillSummaryConfig) -> SkillRegistrar
    private lateinit var prevAppenderFactory: (SkillSummaryConfig) -> SkillAppender

    private val validMd = """
        ---
        name: refactor-auth
        description: Generalized steps for refactoring an authentication module.
        allowed-tools: coding.read
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
        ## Examples
        - Applied to a login flow consolidation.
    """.trimIndent()

    /** Recording fake: captures register calls and never touches the global registry singleton. */
    private class RecordingRegistrar : SkillRegistrar {
        val calls = mutableListOf<Triple<String, String, SkillSummaryConfig.RegisterMode>>()
        var outcome: SkillRegistrar.Outcome = SkillRegistrar.Outcome.DraftOnly
        override suspend fun register(
            draftDir: Path, skillId: String, mode: SkillSummaryConfig.RegisterMode,
        ): SkillRegistrar.Outcome {
            calls.add(Triple(draftDir.toString(), skillId, mode))
            return outcome
        }
        override suspend fun reinstall(targetDir: Path, skillId: String): SkillRegistrar.Outcome = outcome
    }

    private lateinit var registrar: RecordingRegistrar

    @BeforeEach
    fun setup() {
        prevIndexFactory = SkillSummarizer.indexFactory
        prevGeneratorFactory = SkillSummarizer.generatorFactory
        prevRegistrarFactory = SkillSummarizer.registrarFactory
        registrar = RecordingRegistrar()
        SkillSummarizer.indexFactory = { SkillFingerprintIndex(tempDir.resolve("index.json")) }
        SkillSummarizer.generatorFactory = { _, _ -> SkillDraftGenerator(tempDir, llm = { _, _ -> validMd }) }
        SkillSummarizer.registrarFactory = { registrar }
        prevAppenderFactory = SkillSummarizer.appenderFactory
        SkillSummarizer.appenderFactory = { SkillAppender(tempDir, tempDir.resolve("skills-none")) }
    }

    @AfterEach
    fun teardown() {
        SkillSummarizer.indexFactory = prevIndexFactory
        SkillSummarizer.generatorFactory = prevGeneratorFactory
        SkillSummarizer.registrarFactory = prevRegistrarFactory
        SkillSummarizer.appenderFactory = prevAppenderFactory
    }

    private fun capturedEmitter() = mutableListOf<Triple<String, Map<String, Any?>?, String?>>()

    @Test
    @DisplayName("TR-2.5 maybeSummarize emits collect then a decision event when enabled")
    fun emitsCollectAndDecisionEventsWhenEnabled() = runBlocking {
        val emitted = capturedEmitter()
        val status = AgentTaskStatus().apply { submittedTask = "hello" }
        val events = listOf(AgentTaskEvent(type = AgentTaskEvent.TYPE_DONE))

        SkillSummarizer.maybeSummarize(
            status, events,
            { type, detail, text -> emitted.add(Triple(type, detail, text)) },
            SkillSummaryConfig(enabled = true),
        )

        assertEquals(2, emitted.size)
        assertEquals("skill.summarize", emitted[0].first)
        assertEquals("collect", emitted[0].second?.get("action"))
        val decisionAction = emitted[1].second?.get("action")
        assertTrue(decisionAction in listOf("skip", "generate", "append"), "got $decisionAction")
    }

    @Test
    @DisplayName("TR-1.1 maybeSummarize emits nothing when disabled")
    fun emitsNothingWhenDisabled() {
        var called = false
        runBlocking {
            SkillSummarizer.maybeSummarize(
                AgentTaskStatus(), emptyList(),
                { _, _, _ -> called = true },
                SkillSummaryConfig(enabled = false),
            )
        }
        assertFalse(called)
    }

    @Test
    @DisplayName("maybeSummarize emits nothing when registerMode is off")
    fun emitsNothingWhenOff() {
        var called = false
        runBlocking {
            SkillSummarizer.maybeSummarize(
                AgentTaskStatus(), emptyList(),
                { _, _, _ -> called = true },
                SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.OFF),
            )
        }
        assertFalse(called)
    }

    @Test
    @DisplayName("TR-2.2 maybeSummarize never throws even when the emit callback throws (NFR-1)")
    fun neverThrowsWhenEmitFails() {
        runBlocking {
            SkillSummarizer.maybeSummarize(
                AgentTaskStatus(),
                listOf(AgentTaskEvent(type = AgentTaskEvent.TYPE_DONE)),
                { _, _, _ -> error("emit boom") },
                SkillSummaryConfig(enabled = true),
            )
        }
        // reaching here without an exception is the pass condition
    }

    @Test
    @DisplayName("collect event detail carries rounded counters from the context")
    fun collectDetailCarriesCounters() = runBlocking {
        val emitted = capturedEmitter()
        val status = AgentTaskStatus().apply { submittedTask = "t" }
        val events = listOf(
            AgentTaskEvent(seq = 1, type = AgentTaskEvent.TYPE_ROUND, mutations = 2),
            AgentTaskEvent(seq = 2, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read", callId = "c1"),
            AgentTaskEvent(seq = 3, type = AgentTaskEvent.TYPE_TOOL_RESULT, callId = "c1", ok = true),
            AgentTaskEvent(seq = 4, type = AgentTaskEvent.TYPE_DONE),
        )
        SkillSummarizer.maybeSummarize(
            status, events,
            { type, detail, _ -> emitted.add(Triple(type, detail, null)) },
            SkillSummaryConfig(enabled = true),
        )
        val detail = emitted.first { it.second?.get("action") == "collect" }.second!!
        assertEquals(1L, detail["rounds"])
        assertEquals(1, detail["toolCalls"])
        assertEquals(2L, detail["mutations"])
        assertEquals(0, detail["filesChanged"])
        assertTrue(detail.containsKey("durationMs"))
    }

    private fun generateEvents(): List<AgentTaskEvent> = buildList {
        repeat(3) { add(AgentTaskEvent(seq = (it + 1).toLong(), type = AgentTaskEvent.TYPE_ROUND, mutations = 1)) }
        for (i in 1..5) {
            add(AgentTaskEvent(seq = (10 + i).toLong(), type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read", callId = "c$i"))
            add(AgentTaskEvent(seq = (20 + i).toLong(), type = AgentTaskEvent.TYPE_TOOL_RESULT, callId = "c$i", ok = true))
        }
        add(AgentTaskEvent(seq = 99L, type = AgentTaskEvent.TYPE_DONE))
    }

    @Test
    @DisplayName("TR-5.2 GENERATE path (auto) calls registrar and emits a registered event")
    fun generatePathCallsRegistrarWhenAuto() = runBlocking {
        registrar.outcome = SkillRegistrar.Outcome.Installed("refactor-auth", "/tmp/skills/refactor-auth")
        val emitted = capturedEmitter()
        val status = AgentTaskStatus().apply { submittedTask = "refactor the auth module" }
        SkillSummarizer.maybeSummarize(
            status, generateEvents(),
            { type, detail, _ -> emitted.add(Triple(type, detail, null)) },
            SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.AUTO),
        )
        val actions = emitted.map { it.second?.get("action") }
        assertTrue("generate" in actions, "actions=$actions")
        assertTrue("registered" in actions, "actions=$actions")
        assertEquals(1, registrar.calls.size)
        assertEquals(SkillSummaryConfig.RegisterMode.AUTO, registrar.calls[0].third)
    }

    @Test
    @DisplayName("TR-5.1 draft mode emits draft action and never claims registered")
    fun draftModeEmitsDraftAction() = runBlocking {
        registrar.outcome = SkillRegistrar.Outcome.DraftOnly
        val emitted = capturedEmitter()
        val status = AgentTaskStatus().apply { submittedTask = "refactor the auth module" }
        SkillSummarizer.maybeSummarize(
            status, generateEvents(),
            { type, detail, _ -> emitted.add(Triple(type, detail, null)) },
            SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.DRAFT),
        )
        val actions = emitted.map { it.second?.get("action") }
        assertTrue("generate" in actions, "actions=$actions")
        assertTrue("draft" in actions, "actions=$actions")
        assertFalse("registered" in actions, "actions=$actions")
        assertEquals(1, registrar.calls.size)
        assertEquals(SkillSummaryConfig.RegisterMode.DRAFT, registrar.calls[0].third)
    }

    @Test
    @DisplayName("TR-6.1 APPEND path appends to the existing skill and emits an append event")
    fun appendPathAppendsToExistingSkill() {
        runBlocking {
            // Pre-populate the fingerprint index so the decider matches → APPEND.
            val idx = SkillFingerprintIndex(tempDir.resolve("append-index.json"))
            idx.record(SkillFingerprint.tokensOf("refactor the auth module"), "refactor-auth", 0L)
            SkillSummarizer.indexFactory = { idx }

            // Place the target draft so the appender can locate it.
            val targetDir = tempDir.resolve("refactor-auth-100")
            Files.createDirectories(targetDir)
            Files.writeString(targetDir.resolve("SKILL.md"), validMd)
            SkillSummarizer.appenderFactory = { SkillAppender(tempDir, tempDir.resolve("skills-none")) }

            val emitted = capturedEmitter()
            val status = AgentTaskStatus().apply { submittedTask = "refactor the auth module" }
            SkillSummarizer.maybeSummarize(
                status, generateEvents(),
                { type, detail, _ -> emitted.add(Triple(type, detail, null)) },
                SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.DRAFT, cooldownHours = 0L),
            )
            val actions = emitted.map { it.second?.get("action") }
            assertTrue("append" in actions, "actions=$actions")
            val appendEvent = emitted.first { it.second?.get("action") == "append" }
            assertEquals("refactor-auth", appendEvent.second?.get("targetSkillId"))
            assertEquals(true, appendEvent.second?.get("appended"))
            // The appender wrote the idempotency marker into the target draft file.
            assertTrue(Files.readString(targetDir.resolve("SKILL.md")).contains("<!-- task-id:"))
        }
    }

    @Test
    @DisplayName("TR-6.1 end-to-end: first run generates and records fingerprint, second similar run appends")
    fun consecutiveSimilarRunsGenerateThenAppend() {
        runBlocking {
            // Shared persistent index file: the second run must see the first run's record.
            val idxFile = tempDir.resolve("e2e-index.json")
            SkillSummarizer.indexFactory = { SkillFingerprintIndex(idxFile) }
            SkillSummarizer.generatorFactory = { _, _ -> SkillDraftGenerator(tempDir, llm = { _, _ -> validMd }) }
            SkillSummarizer.appenderFactory = { SkillAppender(tempDir, tempDir.resolve("skills-none")) }
            val cfg = SkillSummaryConfig(
                enabled = true,
                registerMode = SkillSummaryConfig.RegisterMode.DRAFT,
                cooldownHours = 0L,
            )

            val first = capturedEmitter()
            val s1 = AgentTaskStatus().apply { submittedTask = "refactor the auth module" }
            SkillSummarizer.maybeSummarize(s1, generateEvents(), { t, d, _ -> first.add(Triple(t, d, null)) }, cfg)
            val firstActions = first.map { it.second?.get("action") }
            assertTrue("generate" in firstActions, "first run should generate: $firstActions")
            assertTrue(Files.exists(idxFile), "fingerprint index must be written after a successful generate")

            val second = capturedEmitter()
            val s2 = AgentTaskStatus().apply { submittedTask = "refactor the auth module" }
            SkillSummarizer.maybeSummarize(s2, generateEvents(), { t, d, _ -> second.add(Triple(t, d, null)) }, cfg)
            val secondActions = second.map { it.second?.get("action") }
            assertTrue("append" in secondActions, "second run should append via the recorded index: $secondActions")
            assertFalse("generate" in secondActions, "second run must not generate a duplicate draft")
        }
    }
}
