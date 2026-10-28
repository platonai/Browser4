package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SystemToolExecutorTest {

    private lateinit var agentToolManager: AgentToolManager
    private lateinit var executor: SystemToolExecutor

    @BeforeEach
    fun setUp() {
        agentToolManager = mockk(relaxed = true)
        executor = SystemToolExecutor(agentToolManager)
    }

    @Test
    @DisplayName("help for help method returns detailed help")
    fun helpForHelpMethodReturnsDetailedHelp() {
        val help = executor.help("help")

        assertNotNull(help)
        assertTrue(help.contains("Get help information"))
        assertTrue(help.contains("help"))
    }

    @Test
    @DisplayName("help with domain and method delegates to agent tool manager")
    fun helpWithDomainAndMethodDelegatesToAgentToolManager() = runBlocking {
        every { agentToolManager.help("fs", "writeString") } returns "File system help"

        val result = executor.help("fs", "writeString")

        assertEquals("File system help", result)
    }

    @Test
    @DisplayName("system help method executes correctly")
    fun systemHelpMethodExecutesCorrectly() = runBlocking {
        every { agentToolManager.help("tab", "click") } returns "Click help text"

        val tc = ToolCall(
            domain = "system",
            method = "help",
            arguments = mutableMapOf("domain" to "tab", "method" to "click")
        )

        val result = executor.callFunctionOn(tc, executor)

        assertEquals("Click help text", result.value)
    }

    @Test
    @DisplayName("skillDoc returns the SKILL.md reference map when no offset is given")
    fun skillDocReadsBundledSkill() {
        val content = executor.skillDoc("SKILL.md")

        assertTrue(content.contains("reference map"), "expected a reference map, got: ${content.take(120)}")
        assertTrue(content.contains("## 7. Reference Map"), "the section map must list skills sections")
        assertTrue(
            content.length <= 2_048,
            "the reference map ships in the model context and must stay bounded, got ${content.length}"
        )
    }

    @Test
    @DisplayName("the SKILL.md index lists the reference docs a long task needs")
    fun skillDocIndexListsReferenceDocs() {
        val index = executor.skillDoc("SKILL.md")

        listOf("bulk-collection.md", "agent-workspace.md", "htmlsnapshot.md", "agent.md").forEach { doc ->
            assertTrue(index.contains(doc), "$doc must be reachable from the index: $index")
        }
        // The index is a MAP: each doc carries the offset where SKILL.md links it, so a
        // follow-up call can load the surrounding section without a full read.
        assertTrue(
            Regex("""- offset \d+: \w[\w.-]*\.md""").containsMatchIn(index),
            "reference docs need their offsets: $index"
        )
    }

    @Test
    @DisplayName("skillDoc pages by code-point offset without overlap")
    fun skillDocPagesWithoutOverlap() {
        val first = executor.skillDoc("SKILL.md", offset = 0)
        val header = Regex("""offset=(\d+) nextOffset=(\d+) totalLength=(\d+)""").find(first)
        assertNotNull(header, "a page must advertise offset/nextOffset/totalLength: ${first.take(120)}")
        val firstStart = header!!.groupValues[1].toInt()
        val nextOffset = header.groupValues[2].toInt()
        val total = header.groupValues[3].toInt()
        assertEquals(0, firstStart)
        assertTrue(total > 50_000, "SKILL.md should be the real document, got totalLength=$total")

        val second = executor.skillDoc("SKILL.md", offset = nextOffset)
        val secondHeader = Regex("""offset=(\d+) nextOffset=(\d+) totalLength=(\d+)""").find(second)!!
        assertEquals(nextOffset, secondHeader.groupValues[1].toInt(), "the second page starts where the first ended")

        // No overlap: the tail of page 1 is not the head of page 2.
        assertFalse(second.contains(first.takeLast(200)), "pages must not repeat each other")
    }

    @Test
    @DisplayName("skillDoc honours an explicit limit and bounds it to 16k")
    fun skillDocHonoursLimit() {
        val big = executor.skillDoc("SKILL.md", offset = 0, limit = 16_000)
        assertTrue(big.length in 15_000..16_000, "executor must be able to return a 16k slice, got ${big.length}")

        val small = executor.skillDoc("SKILL.md", offset = 0, limit = 2_000)
        assertTrue(small.length <= 2_100, "a small limit must bound the result, got ${small.length}")

        val clamped = executor.skillDoc("SKILL.md", offset = 0, limit = 999_999)
        assertTrue(clamped.length <= 16_100, "limit must be capped at 16k, got ${clamped.length}")
    }

    @Test
    @DisplayName("skillDoc takes offset/limit through the tool call arguments")
    fun skillDocAcceptsPagingArguments() = runBlocking {
        val tc = ToolCall(
            domain = "system",
            method = "skillDoc",
            arguments = mutableMapOf("name" to "SKILL.md", "offset" to 0, "limit" to 1_000),
        )

        val text = executor.callFunctionOn(tc, executor).value?.toString() ?: ""

        assertTrue(text.startsWith("SKILL.md · offset=0 "), text.take(80))
        assertTrue(text.length <= 1_100, "limit must be honoured by the executor, got ${text.length}")
    }

    @Test
    @DisplayName("skillDoc serves the distilled quickstart.md resident reference")
    fun skillDocReadsQuickstart() {
        val content = executor.skillDoc("quickstart.md")

        assertTrue(content.contains("Core Loop"), "quickstart must carry the core loop, got: ${content.take(80)}")
        assertTrue(content.contains("snapshot vs htmlsnapshot"), "quickstart must carry the key decision tree")
        assertTrue(
            content.length < 20_000,
            "quickstart must stay small (resident prompt budget), got ${content.length} chars"
        )
    }

    @Test
    @DisplayName("quickstart stays in sync with SKILL.md on key facts (distillation drift guard)")
    fun quickstartStaysInSyncWithSkill() {
        // Read the documents themselves: `skillDoc` pages a long document, and the
        // drift guard compares content, not a slice.
        val quickstart = executor.skillDocStrict("quickstart.md") ?: error("quickstart.md must resolve")
        val skill = executor.skillDocStrict("SKILL.md") ?: error("SKILL.md must resolve")
        listOf(
            "DOM_LOAD_AND_SELECT", "--auto-diff", "generate-locator",
            "--headless", "BROWSER4_RUNTIME_DIR", "htmlsnapshot",
        ).forEach { token ->
            assertTrue(
                quickstart.contains(token) && skill.contains(token),
                "$token must appear in both quickstart.md and SKILL.md"
            )
        }
    }

    @Test
    @DisplayName("skillDocStrict strips frontmatter for the resident embed")
    fun skillDocStrictStripsFrontmatter() {
        val content = executor.skillDocStrict("quickstart.md")

        assertNotNull(content, "quickstart must resolve for the resident prompt")
        val resident = content!!
        assertFalse(resident.startsWith("---"), "resident embed must not carry YAML frontmatter")
        assertFalse(resident.contains("x-role:"), "frontmatter fields must not leak into the resident prompt")
        assertTrue(resident.contains("Core Loop"), "the body must survive frontmatter stripping")
    }

    @Test
    @DisplayName("skillDocStrict returns null for a missing document (caller decides fallback)")
    fun skillDocStrictReturnsNullWhenMissing() {
        assertNull(executor.skillDocStrict("does-not-exist.md"))
        assertNotNull(executor.skillDocStrict("SKILL.md"), "existing docs must resolve")
        assertNotNull(executor.skillDocStrict("quickstart.md"), "quickstart must resolve for the resident prompt")
    }

    @Test
    @DisplayName("skillDoc resolves reference docs under references/ (latent path bug fixed)")
    fun skillDocResolvesReferenceDocs() {
        val snapshot = executor.skillDoc("snapshot.md")
        assertTrue(
            snapshot.contains("snapshot"),
            "reference docs live under skills/browser4-cli/references/ and must resolve, got: ${snapshot.take(80)}"
        )
        val xsql = executor.skillDoc("x-sql.md")
        assertTrue(xsql.contains("X-SQL"), "x-sql.md must resolve too, got: ${xsql.take(80)}")
    }

    @Test
    @DisplayName("skillDocMetadata returns frontmatter only, not the full SKILL.md body")
    fun skillDocMetadataReturnsFrontmatterOnly() {
        val metadata = executor.skillDocMetadata("SKILL.md")

        assertTrue(metadata.startsWith("Skill: SKILL.md"), "expected metadata header, got: ${metadata.take(60)}")
        assertTrue(metadata.contains("name: browser4-cli"), "metadata must include the skill name")
        assertTrue(metadata.contains("description:"), "metadata must include the description")
        assertFalse(
            metadata.contains("## 1. Core Loop"),
            "metadata must not embed the full SKILL.md body"
        )
        assertTrue(metadata.length < 1_000, "metadata must stay small, got ${metadata.length} chars")
    }

    @Test
    @DisplayName("skillDoc lists available documents for an unknown name")
    fun skillDocUnknownNameListsAvailable() {
        val content = executor.skillDoc("does-not-exist.md")

        assertTrue(content.contains("Skill document not found"))
        assertTrue(content.contains("SKILL.md"))
        assertTrue(content.contains("x-sql.md"))
    }

    @Test
    @DisplayName("skillDoc rejects path traversal")
    fun skillDocRejectsPathTraversal() {
        val thrown = runCatching { executor.skillDoc("../../secret") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got: $thrown")
    }

    @Test
    @DisplayName("taskComplete executes and returns a confirmation")
    fun taskCompleteReturnsConfirmation() = runBlocking {
        val tc = ToolCall(
            domain = "system",
            method = "taskComplete",
            arguments = mutableMapOf(
                "summary" to "Task done",
                "keyFindings" to listOf("k1"),
                "filesChanged" to listOf("f.md"),
                "problems" to listOf("p1"),
            )
        )

        val text = executor.callFunctionOn(tc, executor).value?.toString() ?: ""

        assertTrue(text.contains("Task marked complete"), text)
        assertTrue(text.contains("Task done"), text)
    }

    @Test
    @DisplayName("taskComplete rejects a blank summary")
    fun taskCompleteRejectsBlankSummary() {
        val thrown = runCatching { executor.taskComplete("   ", null, null, null) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got: $thrown")
    }

    @Test
    @DisplayName("TaskCompletion parses native tool-call arguments JSON")
    fun taskCompletionFromJsonParses() {
        val completion = TaskCompletion.fromJson(
            """{"summary":"done","keyFindings":["k1"],"filesChanged":["f1"],"problems":["p1"]}"""
        )

        assertEquals("done", completion.summary)
        assertTrue(completion.keyFindings == listOf("k1"), "keyFindings: ${completion.keyFindings}")
        assertTrue(completion.filesChanged == listOf("f1"), "filesChanged: ${completion.filesChanged}")
        assertTrue(completion.problems == listOf("p1"), "problems: ${completion.problems}")
    }

    @Test
    @DisplayName("domain property is system")
    fun domainPropertyIsSystem() {
        assertEquals("system", executor.domain)
    }
}
