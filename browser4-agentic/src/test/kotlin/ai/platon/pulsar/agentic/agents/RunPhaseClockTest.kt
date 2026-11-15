package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * B2: the phases around the tool loop are measured with boundary marks.
 *
 * The wiring into the runner is thin (five `mark` calls and one `snapshot()`); the
 * arithmetic is what can be wrong, so it is pinned here — with an injected clock, so no
 * test sleeps.
 *
 * The invariant that matters is the **partition**: `setup + run + merge + finalize` must
 * equal the wall clock, otherwise a phase silently swallows time and the numbers stop
 * explaining where the task's seconds went (which is the whole point of the change).
 */
@Tag("Fast")
@DisplayName("Run phase clock (B2)")
class RunPhaseClockTest {

    private var now = 0L

    private fun clock(): Long = now

    private fun advance(ms: Long) {
        now += ms
    }

    /** setup 100 → isolation 3000 → setup 50 → run 20000 → merge 200 → finalize 80. */
    private fun measuredRun(): RunPhaseTimings {
        val c = RunPhaseClock(::clock)
        advance(100)
        c.mark(RunPhase.ISOLATION)
        advance(3_000)
        c.mark(RunPhase.SETUP)
        advance(50)
        c.mark(RunPhase.RUN)
        advance(20_000)
        c.mark(RunPhase.MERGE)
        advance(200)
        c.mark(RunPhase.FINALIZE)
        advance(80)
        return c.snapshot()
    }

    @Test
    @DisplayName("the phases partition the wall clock and isolation is a share of setup")
    fun phasesPartitionTheWallClock() {
        val p = measuredRun()

        // setup is reopened after the worktree work: 100 + 3000 + 50.
        assertEquals(3_150L, p.setupMs, "isolation time counts toward setup too")
        assertEquals(3_000L, p.isolationMs, "and is reported on its own as its share")
        assertEquals(20_000L, p.runMs)
        assertEquals(200L, p.mergeMs)
        assertEquals(80L, p.finalizeMs)
        assertEquals(23_430L, p.wallMs)

        assertEquals(
            p.wallMs,
            p.setupMs + p.runMs + p.mergeMs + p.finalizeMs,
            "a phase that does not partition the wall clock is a measurement bug",
        )
        assertTrue(p.setupMs >= p.isolationMs, "isolation can never exceed the setup that contains it")
    }

    @Test
    @DisplayName("a reopened phase keeps accumulating instead of restarting")
    fun reopenedPhaseAccumulates() {
        val c = RunPhaseClock(::clock)
        advance(10)
        c.mark(RunPhase.RUN)
        advance(20)
        c.mark(RunPhase.SETUP)
        advance(30)
        c.mark(RunPhase.FINALIZE)

        val p = c.snapshot()
        assertEquals(40L, p.setupMs, "10 before the run plus 30 after it")
        assertEquals(20L, p.runMs)
    }

    @Test
    @DisplayName("snapshot closes the open phase, so two snapshots agree")
    fun snapshotIsIdempotent() {
        val c = RunPhaseClock(::clock)
        advance(500)
        val first = c.snapshot()
        val second = c.snapshot()

        assertEquals(first, second, "closing an already closed phase must not add time twice")
        assertEquals(500L, first.wallMs)
        assertEquals(500L, first.setupMs, "the initial segment belongs to setup")
    }

    @Test
    @DisplayName("a clock that steps backwards is clamped, never negative")
    fun backwardsClockIsClamped() {
        val c = RunPhaseClock(::clock)
        advance(100)
        c.mark(RunPhase.RUN)
        now -= 500 // NTP step / WSL clock jump
        c.mark(RunPhase.MERGE)
        advance(40)
        c.mark(RunPhase.FINALIZE)

        val p = c.snapshot()

        // A negative bucket would subtract from the wall clock and make a slow phase look
        // free, so every number is clamped at zero. The partition invariant is only
        // promised for a monotonic clock (asserted above) — a jump may lose the span it
        // crossed, and losing it is better than crediting negative time to a phase.
        val values = listOf(p.wallMs, p.setupMs, p.isolationMs, p.runMs, p.mergeMs, p.finalizeMs)
        assertTrue(values.all { it >= 0 }, "no phase may report negative time: $values")
        assertEquals(0L, p.runMs, "the backwards step closes the phase with zero, not -500")
    }

    @Test
    @DisplayName("the map is flat primitives so the result JSON needs no custom serializer")
    fun mapShape() {
        val map = measuredRun().toMap()

        assertEquals(23_430L, map["wallMs"])
        assertEquals(3_150L, map["setupMs"])
        assertEquals(3_000L, map["isolationMs"])
        assertEquals(20_000L, map["runMs"])
        assertEquals(200L, map["mergeMs"])
        assertEquals(80L, map["finalizeMs"])
        assertTrue(map.values.all { it is Long }, "every phase is a number on the wire")

        // The carry path converts the map with Jackson (the backend's mapper); the values
        // only have to be primitives, not domain objects.
        val json = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper()
            .writeValueAsString(map)
        assertTrue(json.contains("\"isolationMs\":3000"), json)
        assertTrue(json.contains("\"wallMs\":23430"), json)
    }
}
