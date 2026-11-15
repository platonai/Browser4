package ai.platon.pulsar.coding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * C1: one Maven process per workspace, and the speculative build that fills the model wait.
 *
 * The registry is coordination logic, so the tests drive it with gates ([CompletableDeferred])
 * instead of sleeping: every case pins *which* caller runs Maven and which ones join, wait or
 * are cancelled. A timing-based test here would be flaky and would prove nothing about the
 * property that matters — that two builds never touch the same `target/` at once.
 */
@Tag("Fast")
@DisplayName("Speculative build coordination (C1)")
class SpeculativeBuildTest {

    private fun result(exitCode: Int = 0, module: String = "m") = BuildResult(
        module = module,
        goals = "compile",
        exitCode = exitCode,
        output = "",
        diagnostics = emptyList(),
    )

    private fun registry(enabled: Boolean = false) =
        SpeculativeBuild(CoroutineScope(Dispatchers.Default), enabledProvider = { enabled })

    private suspend fun awaitInFlight(registry: SpeculativeBuild, key: String) =
        withTimeout(2_000) { while (!registry.inFlight(key)) delay(1) }

    private suspend fun awaitIdle(registry: SpeculativeBuild, key: String) =
        withTimeout(2_000) { while (registry.inFlight(key)) delay(1) }

    @Test
    @DisplayName("identical inputs join one build: one Maven process, one answer")
    fun identicalBuildsJoin() = runBlocking {
        val registry = registry()
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val key = "ws-join"

        val owner = async(Dispatchers.Default) {
            registry.run(key, "fp") { started.incrementAndGet(); gate.await(); result(exitCode = 0) }
        }
        awaitInFlight(registry, key)
        val joiner = async(Dispatchers.Default) {
            registry.run(key, "fp") { started.incrementAndGet(); result(exitCode = 42) }
        }
        delay(100)

        assertEquals(1, started.get(), "the joiner must not run a second build")
        gate.complete(Unit)

        assertEquals(0, owner.await().exitCode)
        assertEquals(
            0,
            joiner.await().exitCode,
            "the joiner receives the running build's answer, never the answer of a block it did not run",
        )
        assertEquals(1, started.get())
        assertEquals(1, registry.stats().builds)
        assertEquals(0, registry.stats().speculativeBuilds, "both callers were real requests")
        assertEquals(1, registry.stats().reused)
    }

    @Test
    @DisplayName("different inputs wait for the running build instead of racing its target/")
    fun differentInputsSerialize() = runBlocking {        val registry = registry()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val firstGate = CompletableDeferred<Unit>()
        val key = "ws-serialize"

        val first = async(Dispatchers.Default) {
            registry.run(key, "fpA") {
                peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                firstGate.await()
                active.decrementAndGet()
                result(exitCode = 0)
            }
        }
        awaitInFlight(registry, key)
        val second = async(Dispatchers.Default) {
            registry.run(key, "fpB") {
                peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
                result(exitCode = 7)
            }
        }
        delay(100)

        assertEquals(1, registry.stats().builds, "the second build must wait, not start")
        firstGate.complete(Unit)

        assertEquals(0, first.await().exitCode)
        assertEquals(7, second.await().exitCode, "it still runs its own build afterwards")
        assertEquals(1, peak.get(), "two Maven builds must never overlap in one workspace")
        assertEquals(2, registry.stats().builds)
    }

    @Test
    @DisplayName("two workspaces build at the same time: the shared decide lock is not held across a build")
    fun distinctWorkspacesRunConcurrently() = runBlocking {
        // Regression guard for `decideLock`: one global mutex is only safe because it guards the
        // check-and-insert (a map lookup, microseconds) and never the build itself. It replaces a
        // ConcurrentHashMap<String, Mutex> that leaked one mutex per workspace ever seen.
        val registry = registry()
        val firstGate = CompletableDeferred<Unit>()
        val secondEntered = AtomicInteger()

        val first = async(Dispatchers.Default) {
            registry.run("ws-a", "fpA") { firstGate.await(); result(exitCode = 0) }
        }
        awaitInFlight(registry, "ws-a")

        val second = async(Dispatchers.Default) {
            registry.run("ws-b", "fpB") { secondEntered.incrementAndGet(); result(exitCode = 1) }
        }
        withTimeout(2_000) { while (secondEntered.get() == 0) delay(1) }

        assertEquals(1, second.await().exitCode)
        assertTrue(
            registry.inFlight("ws-a"),
            "the other workspace must still be building — a shared lock held across block() would serialize them",
        )
        firstGate.complete(Unit)
        assertEquals(0, first.await().exitCode)
        assertEquals(2, registry.stats().builds)
    }

    @Test
    @DisplayName("cancel drops a speculative flight but never a caller's own build")
    fun cancelOnlySpeculative() = runBlocking {
        val registry = registry(enabled = true)
        val gate = CompletableDeferred<Unit>()
        val key = "ws-cancel"

        assertTrue(registry.schedule(key, "fp") { gate.await(); result() })
        awaitInFlight(registry, key)
        assertTrue(registry.cancel(key), "a speculative build is cancellable: its sources changed")
        awaitIdle(registry, key)
        assertEquals(1, registry.stats().cancelled)

        // A tool call's own build is not cancellable: killing it would fail a real request.
        val ownGate = CompletableDeferred<Unit>()
        val own = async(Dispatchers.Default) {
            registry.run(key, "fp2") { ownGate.await(); result(exitCode = 0) }
        }
        awaitInFlight(registry, key)
        assertFalse(registry.cancel(key), "an on-demand build must not be cancelled by a write")
        ownGate.complete(Unit)
        assertEquals(0, own.await().exitCode)
    }

    @Test
    @DisplayName("a cancelled speculative build is never joined, even for the same inputs")
    fun cancelledFlightIsNotJoined() = runBlocking {
        val registry = registry(enabled = true)
        val gate = CompletableDeferred<Unit>()
        val key = "ws-stale"

        assertTrue(registry.schedule(key, "fp") { gate.await(); result(exitCode = 99) })
        awaitInFlight(registry, key)
        assertTrue(registry.cancel(key))

        val started = AtomicInteger()
        val fresh = async(Dispatchers.Default) {
            registry.run(key, "fp") { started.incrementAndGet(); result(exitCode = 0) }
        }

        assertEquals(
            0,
            fresh.await().exitCode,
            "a cancelled flight describes sources that are gone: the caller must rebuild",
        )
        assertEquals(1, started.get())
        assertEquals(0, registry.stats().reused, "nothing was joined")
    }

    @Test
    @DisplayName("the feature switch gates speculative starts, not single-flight")
    fun switchGatesSpeculativeStarts() = runBlocking {
        val off = registry(enabled = false)
        assertFalse(off.schedule("ws-off", "fp") { result() })
        assertEquals(0, off.stats().builds, "a disabled feature must not start Maven")

        val on = registry(enabled = true)
        val gate = CompletableDeferred<Unit>()
        assertTrue(on.schedule("ws-on", "fp") { gate.await(); result() })
        awaitInFlight(on, "ws-on")
        assertEquals(1, on.stats().speculativeBuilds, "a scheduled build is a speculative one")
        assertFalse(on.schedule("ws-on", "fp2") { result() }, "a busy workspace is not scheduled twice")
        gate.complete(Unit)
        awaitIdle(on, "ws-on")
    }

    @Test
    @DisplayName("a failed build fails its joiners too — they asked for the same sources")
    fun failurePropagatesToJoiners() = runBlocking {
        val registry = registry()
        val gate = CompletableDeferred<Unit>()
        val key = "ws-fail"
        val boom = IllegalStateException("mvn died")

        val owner = async(Dispatchers.Default) {
            runCatching { registry.run(key, "fp") { gate.await(); throw boom } }
        }
        awaitInFlight(registry, key)
        val joiner = async(Dispatchers.Default) {
            runCatching { registry.run(key, "fp") { result() } }
        }
        delay(50)
        gate.complete(Unit)

        owner.await()
        val joined = joiner.await()
        assertTrue(joined.isFailure, "the joiner must not be handed a success")
        assertEquals("mvn died", joined.exceptionOrNull()?.message)
        assertEquals(1, registry.stats().failed)
    }

    @Test
    @DisplayName("stats carry the keep-or-drop ratio the plan decides on")
    fun statsShape() = runBlocking {
        val registry = registry(enabled = true)
        val gate = CompletableDeferred<Unit>()
        val key = "ws-stats"

        assertTrue(registry.schedule(key, "fp") { gate.await(); result() })
        awaitInFlight(registry, key)
        val joiner = async(Dispatchers.Default) { registry.run(key, "fp") { result(exitCode = 1) } }
        delay(50)
        gate.complete(Unit)
        joiner.await()

        val stats = registry.stats()
        assertEquals(1, stats.builds)
        assertEquals(1, stats.speculativeBuilds)
        assertEquals(1, stats.reused)
        assertEquals(1.0, stats.reuseRatio, "the guessed build was used by the request: keep the feature")
        assertEquals(1.0, stats.toMap()["reuseRatio"])
        assertEquals(stats.speculativeMs, stats.toMap()["speculativeMs"], "the CPU bill travels too")
        assertEquals(0, stats.cancelled)
        assertEquals(0, stats.failed)

        // An empty registry must not divide by zero.
        assertEquals(0.0, SpeculativeBuild(CoroutineScope(Dispatchers.Default)).stats().reuseRatio)
    }

    @Test
    @DisplayName("a task reports the delta between two snapshots, never the process total")
    fun statsDelta() {
        val earlier = SpeculativeBuild.Stats(
            builds = 3, speculativeBuilds = 2, reused = 1, cancelled = 0, failed = 1, speculativeMs = 900,
        )
        val later = SpeculativeBuild.Stats(
            builds = 5, speculativeBuilds = 3, reused = 2, cancelled = 1, failed = 1, speculativeMs = 1_500,
        )

        val delta = later.deltaSince(earlier)
        assertEquals(2, delta.builds)
        assertEquals(1, delta.speculativeBuilds)
        assertEquals(1, delta.reused)
        assertEquals(1, delta.cancelled)
        assertEquals(0, delta.failed)
        assertEquals(600L, delta.speculativeMs)

        // `resetStats` between the snapshots must not produce negatives in the report.
        val clamped = earlier.deltaSince(later)
        assertEquals(0, clamped.builds)
        assertEquals(0, clamped.speculativeMs)
    }

    @Test
    @DisplayName("the CPU a guess spent is accounted")
    fun speculativeTimeIsMeasured() = runBlocking {
        val registry = registry(enabled = true)
        val gate = CompletableDeferred<Unit>()
        val key = "ws-ms"

        assertTrue(registry.schedule(key, "fp") { gate.await(); result() })
        awaitInFlight(registry, key)
        delay(25)
        gate.complete(Unit)
        awaitIdle(registry, key)

        // 25 ms is above the Windows timer granularity, so a zero here would mean the
        // accounting never ran (the metric a keep-or-drop decision reads).
        assertTrue(
            registry.stats().speculativeMs >= 20,
            "the guess cost measurable time, was ${registry.stats().speculativeMs}",
        )
    }
}
