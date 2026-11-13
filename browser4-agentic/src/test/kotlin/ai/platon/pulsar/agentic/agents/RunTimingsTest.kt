package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.inference.chat.RoundTiming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * N3: the run's four buckets are accumulated in-process and travel with the task result.
 *
 * The wiring into `agent result` is thin (status -> result -> renderer); the arithmetic is
 * what can be wrong, so it is pinned here: build tools must be split out of the tool
 * bucket (a `coding.mvnBuild` body dominates a self-development run), rounds contribute
 * model/callback time and tokens, and a reset must clear everything.
 */
@Tag("Fast")
@DisplayName("Run timings accumulator (N3)")
class RunTimingsTest {

    private fun round(
        modelMs: Long = 1_000,
        toolsMs: Long = 100,
        callbacksMs: Long = 10,
        outTokens: Int = 200,
        cachedInTokens: Int = 5_000,
        inTokens: Int = 7_000,
    ) = RoundTiming(
        round = 1,
        calls = 1,
        modelMs = modelMs,
        toolsMs = toolsMs,
        callbacksMs = callbacksMs,
        outTokens = outTokens,
        cachedInTokens = cachedInTokens,
        inTokens = inTokens,
    )

    @Test
    @DisplayName("build tools are split out of the tool bucket, in both name spellings")
    fun buildToolsAreSplitOut() {
        val acc = RunTimingsAccumulator()
        acc.onTool("coding_mvnBuild", 5_000)
        acc.onTool("coding.verify", 1_200)
        acc.onTool("coding_readLines", 20)
        acc.onTool("coding_grep", 5)

        val timings = acc.snapshot()

        assertEquals(6_200L, timings.buildMs, "both build entry points count as build time")
        assertEquals(25L, timings.toolMs, "every other tool body is tool time")
        assertTrue(RunTimingsAccumulator.isBuildTool("coding_mvnBuild"))
        assertTrue(RunTimingsAccumulator.isBuildTool("coding.mvnBuild"))
        assertFalse(RunTimingsAccumulator.isBuildTool("coding_readLines"))
    }

    @Test
    @DisplayName("rounds add model time, callback time and tokens — but not tool time")
    fun roundsAndTokens() {
        val acc = RunTimingsAccumulator()
        acc.onRound(round())
        acc.onRound(round(modelMs = 2_000, outTokens = 300, cachedInTokens = 1_000, inTokens = 2_000))

        val timings = acc.snapshot()

        assertEquals(2, timings.rounds)
        assertEquals(3_000L, timings.modelMs)
        assertEquals(20L, timings.callbackMs)
        // `toolsMs` is reported by the same event, but tool bodies are attributed by
        // onTool: counting them here as well would double the bucket.
        assertEquals(0L, timings.toolMs, "round events must not inflate the tool bucket")
        assertEquals(500L, timings.outputTokens.toLong())
        assertEquals(6_000L, timings.cachedInputTokens)
        assertEquals(9_000L, timings.inputTokens)
    }

    @Test
    @DisplayName("totalMs is the sum of the four buckets and the map is what the result carries")
    fun snapshotShape() {
        val acc = RunTimingsAccumulator()
        acc.onTool("coding_mvnBuild", 5_000)
        acc.onTool("coding_readLines", 120)
        acc.onRound(round(modelMs = 800, callbacksMs = 30, outTokens = 640, cachedInTokens = 51_200, inTokens = 52_000))

        val timings = acc.snapshot()
        assertEquals(5_000 + 120 + 800 + 30, timings.totalMs.toInt())

        val map = timings.toMap()
        assertEquals(1, map["rounds"])
        assertEquals(timings.totalMs, map["totalMs"])
        assertEquals(5_000L, map["buildMs"])
        assertEquals(120L, map["toolMs"])
        assertEquals(30L, map["callbackMs"])
        assertEquals(800L, map["modelMs"])
        @Suppress("UNCHECKED_CAST")
        val tokens = map["tokens"] as Map<String, Any?>
        assertEquals(52_000L, tokens["in"])
        assertEquals(51_200L, tokens["cachedIn"])
        assertEquals(640L, tokens["out"], "token counts are Long on the wire")
    }

    @Test
    @DisplayName("the map serializes to the JSON shape a client reads")
    fun mapIsJsonSerializable() {
        val acc = RunTimingsAccumulator()
        acc.onTool("coding_mvnBuild", 1_000)
        acc.onRound(round())

        // The carry path converts the map with Jackson (the backend's mapper); this only
        // has to prove the values are primitives, not domain objects.
        val json = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper()
            .writeValueAsString(acc.snapshot().toMap())

        assertTrue(json.contains("\"rounds\":1"), json)
        assertTrue(json.contains("\"buildMs\":1000"), json)
        assertTrue(json.contains("\"tokens\":{"), json)
        assertTrue(json.contains("\"out\":200"), json)
    }

    @Test
    @DisplayName("reset clears every bucket so a reused agent does not carry the previous run")
    fun resetClearsEverything() {
        val acc = RunTimingsAccumulator()
        acc.onTool("coding_mvnBuild", 9_000)
        acc.onRound(round())

        acc.reset()

        val timings = acc.snapshot()
        assertEquals(0, timings.rounds)
        assertEquals(0L, timings.buildMs)
        assertEquals(0L, timings.toolMs)
        assertEquals(0L, timings.modelMs)
        assertEquals(0L, timings.totalMs)
        assertEquals(0L, timings.outputTokens.toLong())
    }

    @Test
    @DisplayName("a negative duration from a clock hiccup is clamped, never subtracted")
    fun negativeDurationsAreClamped() {
        val acc = RunTimingsAccumulator()
        acc.onTool("coding_readLines", -50)
        acc.onRound(round(modelMs = -10, callbacksMs = -5, outTokens = -1, cachedInTokens = -2, inTokens = -3))

        val timings = acc.snapshot()
        assertEquals(0L, timings.toolMs)
        assertEquals(0L, timings.modelMs)
        assertEquals(0L, timings.callbackMs)
        assertEquals(0L, timings.outputTokens.toLong())
        assertEquals(1, timings.rounds, "the round happened even if its numbers were unusable")
    }

    @Test
    @DisplayName("an agent that measured nothing reports null instead of a zero-filled block")
    fun emptyRunIsNotReported() {
        // The agent's own accessor is `snapshot().takeIf { ... > 0 }`; mirror it here so
        // the contract "null means nothing measured" is explicit.
        val snapshot = RunTimingsAccumulator().snapshot()
        val reported = snapshot.takeIf { it.rounds > 0 || it.toolMs > 0 || it.buildMs > 0 }
        assertNull(reported)
    }
}
