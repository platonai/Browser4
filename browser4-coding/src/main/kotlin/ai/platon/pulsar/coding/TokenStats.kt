package ai.platon.pulsar.coding

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Heuristic LLM token estimator for tool-call input/output text.
 *
 * Zero dependencies. Approximates BPE tokenizers (cl100k/o200k class) within
 * roughly ±25% for prose and source code — good enough for cost accounting
 * and efficiency analysis, NOT for billing.
 *
 * Model:
 * - Identifiers are split at camelCase boundaries; each sub-word of length L
 *   costs ceil(L/5) tokens (English words average ~5 chars/sub-word).
 * - Digit runs cost ceil(n/3) (BPE groups digits 1-3 per token).
 * - Each CJK character costs 1 token.
 * - Each symbol/punctuation character costs 1 token (slightly overestimates
 *   common merges like `::`, `->`, `==`).
 * - Whitespace largely merges into neighboring tokens; counted as 1 per 8 chars.
 */
object TokenEstimator {

    // camelCase-aware chunking: Capitalized words, acronyms, lowercase runs, digit runs, single CJK, single symbol
    private val CHUNK = Regex("[A-Z]+[a-z]*|[a-z]+|[0-9]+|[\\u3400-\\u4dbf\\u4e00-\\u9fff]|[^\\s]")

    /** Estimated token count of [text]; 0 for empty input. */
    fun estimateTokens(text: String): Long {
        if (text.isEmpty()) return 0L
        var tokens = 0L
        var nonWs = 0
        for (m in CHUNK.findAll(text)) {
            val s = m.value
            nonWs += s.length
            tokens += when (s[0]) {
                in '0'..'9' -> (s.length + 2) / 3
                in '\u3400'..'\u4dbf', in '\u4e00'..'\u9fff' -> 1
                in 'a'..'z', in 'A'..'Z' -> (s.length + 4) / 5
                else -> 1
            }
        }
        tokens += (text.length - nonWs + 7) / 8
        return tokens
    }
}

/**
 * Per-method token statistics accumulator for the `coding` domain.
 *
 * Records, for every tool call: input (serialized arguments) and output
 * (result string) size in chars and estimated tokens, call/error counts,
 * duration, and max sizes. Thread-safe; fixed memory footprint
 * (one entry per distinct method name).
 *
 * Consumed by `coding.tokenStats` so the agent and developers can audit
 * which coding tools consume the most context window.
 */
class CodingTokenStats {

    /** Aggregated statistics for one tool method. Immutable snapshot fields; safe to read. */
    data class MethodStat(
        val method: String,
        val calls: Long,
        val errors: Long,
        val inChars: Long,
        val inTokens: Long,
        val outChars: Long,
        val outTokens: Long,
        val maxInChars: Long,
        val maxOutChars: Long,
        val totalMillis: Long,
    ) {
        val avgOutTokens: Long get() = if (calls > 0) outTokens / calls else 0
        val avgMillis: Long get() = if (calls > 0) totalMillis / calls else 0
    }

    private class MutableStat(val method: String) {
        val calls = AtomicLong()
        val errors = AtomicLong()
        val inChars = AtomicLong()
        val inTokens = AtomicLong()
        val outChars = AtomicLong()
        val outTokens = AtomicLong()
        val maxInChars = AtomicLong()
        val maxOutChars = AtomicLong()
        val totalMillis = AtomicLong()

        fun snapshot() = MethodStat(
            method, calls.get(), errors.get(), inChars.get(), inTokens.get(),
            outChars.get(), outTokens.get(), maxInChars.get(), maxOutChars.get(), totalMillis.get(),
        )
    }

    private val lock = ReentrantReadWriteLock()
    private val methods = ConcurrentHashMap<String, MutableStat>()

    @Volatile
    private var sinceMillis: Long = System.currentTimeMillis()

    /** True when nothing has been recorded yet. */
    val isEmpty: Boolean get() = methods.isEmpty()

    /**
     * Record one tool call.
     *
     * @param method  tool method name (e.g. "read", "shell")
     * @param input   serialized arguments the LLM sent (null → 0)
     * @param output  result text returned to the LLM (null → 0)
     * @param error   true when the call failed
     * @param millis  wall-clock duration of the call
     */
    fun record(method: String, input: String?, output: String?, error: Boolean = false, millis: Long = 0) {
        if (method.isBlank()) return
        lock.read {
            methods.computeIfAbsent(method) { MutableStat(it) }.let { s ->
                s.calls.incrementAndGet()
                if (error) s.errors.incrementAndGet()
                val inLen = input?.length?.toLong() ?: 0L
                val outLen = output?.length?.toLong() ?: 0L
                s.inChars.addAndGet(inLen)
                s.inTokens.addAndGet(TokenEstimator.estimateTokens(input ?: ""))
                s.outChars.addAndGet(outLen)
                s.outTokens.addAndGet(TokenEstimator.estimateTokens(output ?: ""))
                s.maxInChars.accumulateAndGet(inLen) { a, b -> maxOf(a, b) }
                s.maxOutChars.accumulateAndGet(outLen) { a, b -> maxOf(a, b) }
                if (millis > 0) s.totalMillis.addAndGet(millis)
            }
        }
    }

    /** Immutable per-method snapshots, sorted by output tokens (desc). */
    fun snapshot(): List<MethodStat> =
        methods.values.map { it.snapshot() }.sortedByDescending { it.outTokens }

    /** Drop all recorded statistics and restart the "since" clock. */
    fun reset() {
        lock.write {
            methods.clear()
            sinceMillis = System.currentTimeMillis()
        }
    }

    /**
     * Human-readable report: one line per method (sorted by output tokens),
     * plus a total line. Compact — it is itself returned to the LLM.
     */
    fun report(): String {
        val stats = snapshot()
        if (stats.isEmpty()) return "No coding tool calls recorded yet."

        val totalCalls = stats.sumOf { it.calls }
        val totalIn = stats.sumOf { it.inTokens }
        val totalOut = stats.sumOf { it.outTokens }
        val totalErrors = stats.sumOf { it.errors }
        val mins = (System.currentTimeMillis() - sinceMillis) / 60000

        return buildString {
            appendLine("Coding token statistics — $totalCalls calls, $totalErrors errors, since ${mins}m ago")
            appendLine("Estimated tokens: input $totalIn + output $totalOut = ${totalIn + totalOut}")
            appendLine("%-18s %6s %5s %9s %9s %9s %9s".format("method", "calls", "err", "in-tok", "out-tok", "avg-out", "max-out-B"))
            stats.forEach {
                appendLine(
                    "%-18s %6d %5d %9d %9d %9d %9d".format(
                        it.method, it.calls, it.errors, it.inTokens, it.outTokens, it.avgOutTokens, it.maxOutChars
                    )
                )
            }
        }.trimEnd()
    }
}
