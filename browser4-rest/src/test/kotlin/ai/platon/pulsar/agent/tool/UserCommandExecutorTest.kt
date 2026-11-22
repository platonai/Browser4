package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTraceIndex
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A synchronous page visit is a browser operation, so two things must hold: it must not
 * occupy the thread that asked for it (under Spring MVC that is a servlet worker, needed
 * back immediately), and it must not be startable an unbounded number of times — the page
 * load blocks a `Dispatchers.Default` thread, which is sized to the CPU count.
 *
 * The session is mocked and the visit is stopped at its first step: these are the
 * executor's scheduling decisions, not a browser test.
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("sync page visit scheduling")
class UserCommandExecutorTest {

    private val sessionManager: PulsarSessionManager = Mockito.mock(PulsarSessionManager::class.java)

    private fun request() = PageVisitRequest(url = "https://example.com/product/1")

    private fun handlers() = PageEventHandlersFactory.create()

    @Test
    @DisplayName("a sync visit runs on the command dispatcher, not on the caller's thread")
    fun syncVisitLeavesTheCallersThread() {
        val threads = CopyOnWriteArrayList<String>()
        whenever(sessionManager.getOrCreateSession(any<String>(), anyOrNull())).thenAnswer {
            threads += Thread.currentThread().name
            // Stop the visit at its first step — a real one would need a browser.
            throw IllegalStateException("stop the visit here")
        }

        val executor = UserCommandExecutor(sessionManager)
        try {
            val caller = Thread.currentThread().name

            assertFailsWith<IllegalStateException> {
                runBlocking { executor.executePageVisitCommand("s1", request(), handlers()) }
            }

            assertEquals(1, threads.size, "the visit must have been attempted exactly once")
            assertNotEquals(caller, threads.single(), "the visit must not occupy the requesting thread")
        } finally {
            executor.close()
        }
    }

    @Test
    @DisplayName("at most MAX_CONCURRENT_COMMANDS sync visits run at the same time")
    fun syncVisitsAreBounded() {        val parallelism = UserCommandExecutor.MAX_CONCURRENT_COMMANDS
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CountDownLatch(1)
        whenever(sessionManager.getOrCreateSession(any<String>(), anyOrNull())).thenAnswer {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { current -> max(current, now) }
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                inFlight.decrementAndGet()
            }
            throw IllegalStateException("stop the visit here")
        }

        val executor = UserCommandExecutor(sessionManager)
        try {
            runBlocking {
                val visits = (1..(parallelism + 2)).map { i ->
                    async(Dispatchers.Default) {
                        runCatching { executor.executePageVisitCommand("s$i", request(), handlers()) }
                    }
                }

                // Every coroutine reaches the pool and takes a slot; the extra two wait for one.
                delay(1_000)
                assertEquals(parallelism, inFlight.get(), "the pool must be full, never over-subscribed")

                release.countDown()
                visits.awaitAll()
            }

            assertEquals(parallelism, peak.get(), "more visits ran at once than the bound allows")
        } finally {
            release.countDown()
            executor.close()
        }
    }

    // ---- the run trace API (issue #652, gap 5) -------------------------------

    /**
     * A run dir with the two streams a real trace has, plus the prompt dump the API must NOT
     * serve. Returns the executor wired to an index that points at it.
     */
    private fun tracedExecutor(root: Path, taskId: String, lines: List<String>): UserCommandExecutor {
        System.setProperty("browser4.data.dir", root.toString())
        val runDir = root.resolve("logs/agent/2026-11-22/aaaa")
        Files.createDirectories(runDir.resolve("cli-prompt"))
        Files.writeString(runDir.resolve("cli-tool-trace.jsonl"), lines.joinToString("") { "$it\n" })
        Files.writeString(runDir.resolve("cli-usage.jsonl"), """{"requestSeq":1}""" + "\n")
        Files.writeString(runDir.resolve("cli-prompt/1.request.json"), """{"messages":[]}""" + "\n")
        AgentTraceIndex(AgentTraceIndex.indexPath()).record(taskId, runDir)
        return UserCommandExecutor(sessionManager)
    }

    @Test
    @DisplayName("the trace endpoint lists the audit streams and hides the raw prompt dumps")
    fun theTraceListShowsTheAuditStreamsOnly(@TempDir root: Path) {
        val executor = tracedExecutor(root, "task-1", listOf("""{"seq":1}"""))
        try {
            val trace = assertNotNull(executor.trace("task-1"))

            assertEquals("task-1", trace.taskId)
            assertEquals(
                setOf("cli-tool-trace.jsonl", "cli-usage.jsonl"),
                trace.files.map { it.name }.toSet(),
                "a prompt dump carries the whole conversation and is not served",
            )
            assertNull(executor.trace("ghost"), "an unindexed task is not found")
        } finally {
            executor.close()
            System.clearProperty("browser4.data.dir")
        }
    }

    @Test
    @DisplayName("a trace page is redacted again on read, covering a trace an older build wrote")
    fun tracePagesAreRedactedOnRead(@TempDir root: Path) {
        // The file on disk still holds the plaintext: it predates the write-side redaction.
        val executor = tracedExecutor(
            root,
            "task-1",
            listOf(
                """{"seq":1,"arguments":"{\"password\":\"hunter2\"}"}""",
                """{"seq":2,"resultText":"ok"}""",
            ),
        )
        try {
            val page = assertNotNull(executor.traceFile("task-1", "cli-tool-trace.jsonl"))

            assertEquals(2, page.totalLines)
            assertEquals(2, page.lines.size)
            assertFalse(page.lines[0].contains("hunter2"), page.lines[0])
            assertTrue(page.lines[0].contains("***"), page.lines[0])
            assertEquals("""{"seq":2,"resultText":"ok"}""", page.lines[1], "clean lines are untouched")
        } finally {
            executor.close()
            System.clearProperty("browser4.data.dir")
        }
    }

    @Test
    @DisplayName("only *.jsonl streams inside the run dir can be read")
    fun onlyStreamNamesAreReadable(@TempDir root: Path) {
        val executor = tracedExecutor(root, "task-1", listOf("""{"seq":1}"""))
        try {
            assertNull(executor.traceFile("task-1", "cli-prompt/1.request.json"), "a prompt dump is not served")
            assertNull(executor.traceFile("task-1", "../../agent-tasks.jsonl"), "no path traversal")
            assertNull(executor.traceFile("task-1", "absent.jsonl"), "an unknown stream is 404")
            assertNull(executor.traceFile("ghost", "cli-usage.jsonl"), "an unknown task is 404")
        } finally {
            executor.close()
            System.clearProperty("browser4.data.dir")
        }
    }

    @Test
    @DisplayName("trace pages are paged by line and clamped")
    fun tracePagesArePagedAndClamped(@TempDir root: Path) {
        val executor = tracedExecutor(root, "task-1", (1..10).map { """{"seq":$it}""" })
        try {
            val first = assertNotNull(executor.traceFile("task-1", "cli-tool-trace.jsonl", offset = 0, limit = 3))
            assertEquals(listOf("""{"seq":1}""", """{"seq":2}""", """{"seq":3}"""), first.lines)

            val second = assertNotNull(executor.traceFile("task-1", "cli-tool-trace.jsonl", offset = 8, limit = 3))
            assertEquals(listOf("""{"seq":9}""", """{"seq":10}"""), second.lines, "a short last page is not padded")
            assertEquals(10, second.totalLines)

            val clamped = assertNotNull(
                executor.traceFile("task-1", "cli-tool-trace.jsonl", offset = 0, limit = 99_999)
            )
            assertEquals(UserCommandExecutor.TRACE_PAGE_MAX, clamped.limit, "the page size is bounded")
        } finally {
            executor.close()
            System.clearProperty("browser4.data.dir")
        }
    }
}
