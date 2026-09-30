package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TokenStatsTest {

    // ==================== TokenEstimator ====================

    @Test
    @DisplayName("empty text estimates to 0 tokens")
    fun testEmpty() {
        assertEquals(0L, TokenEstimator.estimateTokens(""))
    }

    @Test
    @DisplayName("plain English prose is roughly 4 chars per token")
    fun testEnglishProse() {
        val text = "Execute a shell command. Supports git, cargo, mvn, npm, python, node."
        val tokens = TokenEstimator.estimateTokens(text)
        // Heuristic target: within 50% of chars/4
        val charsPer4 = text.length / 4.0
        assertTrue(tokens in (charsPer4 * 0.5).toLong()..(charsPer4 * 1.8).toLong(),
            "tokens=$tokens charsPer4=$charsPer4")
    }

    @Test
    @DisplayName("camelCase identifiers split into sub-words")
    fun testCamelCase() {
        // CodingAgentFileSystem → Coding|Agent|File|System ≈ 4-8 tokens
        val tokens = TokenEstimator.estimateTokens("CodingAgentFileSystem")
        assertTrue(tokens in 4..8L, "tokens=$tokens")
    }

    @Test
    @DisplayName("each CJK character costs about 1 token")
    fun testCjk() {
        val text = "浏览器自动化" // 6 chars
        val tokens = TokenEstimator.estimateTokens(text)
        assertTrue(tokens in 4..8L, "tokens=$tokens")
    }

    @Test
    @DisplayName("estimation is monotonic in text length")
    fun testMonotonic() {
        val base = "val x = computeValue(input) "
        assertTrue(TokenEstimator.estimateTokens(base) < TokenEstimator.estimateTokens(base + base))
    }

    // ==================== CodingTokenStats ====================

    @Test
    @DisplayName("record accumulates calls, chars and tokens per method")
    fun testRecordAccumulates() {
        val stats = CodingTokenStats()
        stats.record("read", input = "{path=Foo.kt}", output = "line1\nline2\n")
        stats.record("read", input = "{path=Foo.kt}", output = "line3\n")

        val snap = stats.snapshot()
        assertEquals(1, snap.size)
        val read = snap[0]
        assertEquals("read", read.method)
        assertEquals(2L, read.calls)
        assertEquals(0L, read.errors)
        assertEquals("{path=Foo.kt}".length * 2L, read.inChars)
        assertEquals("line1\nline2\nline3\n".length.toLong(), read.outChars)
        assertTrue(read.outTokens > 0)
        assertEquals("line1\nline2\n".length.toLong(), read.maxOutChars)
    }

    @Test
    @DisplayName("failed calls are counted as errors")
    fun testErrorCounting() {
        val stats = CodingTokenStats()
        stats.record("shell", input = "{command=x}", output = "IllegalArgumentException: boom", error = true)
        val snap = stats.snapshot().single()
        assertEquals(1L, snap.calls)
        assertEquals(1L, snap.errors)
    }

    @Test
    @DisplayName("report lists methods sorted by output tokens and includes a total")
    fun testReport() {
        val stats = CodingTokenStats()
        stats.record("read", input = "{p=a.kt}", output = "a".repeat(2000))
        stats.record("glob", input = "{pattern=*.md}", output = "b".repeat(100))
        val report = stats.report()
        assertTrue(report.contains("2 calls"))
        assertTrue(report.indexOf("read") < report.indexOf("glob"), "read (bigger output) must come first")
        assertTrue(report.contains("method"))
        stats.reset()
        assertTrue(stats.report().startsWith("No coding tool calls"))
    }

    @Test
    @DisplayName("concurrent recording from multiple threads is aggregated safely")
    fun testConcurrent() {
        val stats = CodingTokenStats()
        val threads = 8
        val perThread = 1000
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                repeat(perThread) { stats.record("shell", input = "c", output = "out") }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
        val snap = stats.snapshot().single()
        assertEquals((threads * perThread).toLong(), snap.calls)
    }
}
