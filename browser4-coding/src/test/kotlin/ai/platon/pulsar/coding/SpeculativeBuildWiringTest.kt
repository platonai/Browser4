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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * C1 wiring: a build started before the model asked for it must be *the* build the model
 * gets, and two callers of the same workspace must never start two Maven processes.
 *
 * The injected command runner counts invocations — the only way to prove that a build was
 * joined rather than merely fast (the same reason [BuildShortCircuitTest] counts them).
 */
@Tag("Fast")
@DisplayName("Speculative build wiring (C1)")
class SpeculativeBuildWiringTest {

    @TempDir
    lateinit var tmp: Path

    /** A Maven that counts invocations and can hold one of them open while the test decides. */
    private class GatedMaven(private val gateCall: Int) {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var exitCode = 0

        val runner: suspend (CodingAgentShell, String, Long, String?) -> ShellResult = { _, command, _, _ ->
            val call = calls.incrementAndGet()
            if (call == gateCall) {
                entered.complete(Unit)
                release.await()
            }
            ShellResult(
                sessionId = "fake",
                command = command,
                exitCode = exitCode,
                stdout = "",
                stderr = "",
                durationMs = 1,
                timedOut = false,
            )
        }
    }

    private fun workspace(name: String, source: String = "fun main() = Unit"): Path {
        val root = tmp.resolve(name)
        Files.createDirectories(root.resolve("browser4-coding/src/main/kotlin"))
        Files.writeString(root.resolve("pom.xml"), "<project><modules><module>browser4-coding</module></modules></project>")
        Files.writeString(root.resolve("browser4-coding/pom.xml"), "<project><artifactId>browser4-coding</artifactId></project>")
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Foo.kt"), source)
        return root
    }

    private fun registry(enabled: Boolean) =
        SpeculativeBuild(CoroutineScope(Dispatchers.Default), enabledProvider = { enabled })

    private suspend fun awaitIdle(registry: SpeculativeBuild, key: String) =
        withTimeout(2_000) { while (registry.inFlight(key)) delay(5) }

    @Test
    @DisplayName("the build the agent asks for joins the speculative one instead of starting Maven twice")
    fun aSpeculativeBuildIsJoined() = runBlocking {
        val root = workspace("wsJoin")
        val maven = GatedMaven(gateCall = 2)
        val registry = registry(enabled = true)
        val support = MavenBuildSupport(cache = BuildCache(), speculative = registry, commandRunner = maven.runner)
        val shell = CodingAgentShell(baseDir = root)

        // A real build first: it records which module this workspace builds (the spec target)
        // and stores the fingerprint of the current sources.
        assertTrue(support.build(shell, "browser4-coding").success)
        assertEquals(1, maven.calls.get())

        // An edit makes a speculative compile worth doing, and gives it new sources to hash.
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Foo.kt"), "fun main() = 42")
        assertTrue(support.scheduleSpeculativeBuild(shell), "an edited workspace must schedule a compile")
        withTimeout(2_000) { maven.entered.await() }

        // Now the agent asks for exactly that build: it must join, not start a second Maven.
        val asked = async(Dispatchers.Default) { support.build(shell, "browser4-coding") }
        delay(150)
        assertEquals(2, maven.calls.get(), "the requested build must join the speculative one")

        maven.release.complete(Unit)
        val result = asked.await()

        assertTrue(result.success)
        assertEquals(2, maven.calls.get(), "one Maven process served both the guess and the request")
        assertEquals(1, registry.stats().reused, "the on-demand build was served by the speculative one")
        // The joined build publishes its answer, so a later zero-edit build is a cache hit.
        val third = support.build(shell, "browser4-coding")
        assertTrue(third.cached, "the speculative build must populate the cache like any other build")
        assertEquals(2, maven.calls.get())
    }

    @Test
    @DisplayName("a write cancels the speculative build instead of letting it finish stale work")
    fun aWriteCancelsTheSpeculativeBuild() = runBlocking {
        val root = workspace("wsCancel")
        val maven = GatedMaven(gateCall = 2)
        val registry = registry(enabled = true)
        val support = MavenBuildSupport(cache = BuildCache(), speculative = registry, commandRunner = maven.runner)
        val shell = CodingAgentShell(baseDir = root)
        val key = BuildCache.rootKey(root)

        support.build(shell, "browser4-coding")
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Foo.kt"), "fun main() = 43")
        assertTrue(support.scheduleSpeculativeBuild(shell))
        withTimeout(2_000) { maven.entered.await() }

        // What `CodingAgentFileSystem` does on a write: drop the cache entry AND the build
        // that is compiling the tree that just changed.
        support.cancelSpeculativeBuild(root)
        awaitIdle(registry, key)
        assertEquals(1, registry.stats().cancelled)

        // Nothing was cached by the cancelled build, so the next request really runs Maven.
        maven.release.complete(Unit) // the cancelled call never reaches this await, but a later one must not block
        val rebuilt = support.build(shell, "browser4-coding")
        assertFalse(rebuilt.cached, "a cancelled build must not leave a green cache entry")
        assertTrue(rebuilt.success)
    }

    @Test
    @DisplayName("-Dbrowser4.agent.build.speculative off: no background Maven, on-demand builds unchanged")
    fun theSwitchDisablesSpeculation() = runBlocking {
        val root = workspace("wsOff")
        val maven = GatedMaven(gateCall = Int.MAX_VALUE)
        val registry = registry(enabled = false)
        val support = MavenBuildSupport(cache = BuildCache(), speculative = registry, commandRunner = maven.runner)
        val shell = CodingAgentShell(baseDir = root)

        support.build(shell, "browser4-coding")
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Foo.kt"), "fun main() = 44")

        assertFalse(support.scheduleSpeculativeBuild(shell), "the feature is off")
        assertEquals(1, maven.calls.get(), "no background build may start")
        assertEquals(0, registry.stats().speculativeBuilds, "the switch gates speculative starts")

        assertTrue(support.build(shell, "browser4-coding").success)
        assertEquals(2, maven.calls.get(), "the on-demand path is untouched")
    }

    @Test
    @DisplayName("two concurrent identical builds start one Maven even with the feature off")
    fun concurrentIdenticalBuildsStartOneMaven() = runBlocking {
        val root = workspace("wsSingle")
        val maven = GatedMaven(gateCall = 1)
        // Feature OFF on purpose: single-flight is a correctness property (one `target/`),
        // not part of the speculative optimisation.
        val registry = registry(enabled = false)
        val support = MavenBuildSupport(cache = BuildCache(), speculative = registry, commandRunner = maven.runner)
        val shell = CodingAgentShell(baseDir = root)

        val first = async(Dispatchers.Default) { support.build(shell, "browser4-coding") }
        withTimeout(2_000) { maven.entered.await() }
        val second = async(Dispatchers.Default) { support.build(shell, "browser4-coding") }
        delay(150)

        assertEquals(1, maven.calls.get(), "the second caller must join the build in progress")
        maven.release.complete(Unit)

        assertTrue(first.await().success)
        assertTrue(second.await().success)
        assertEquals(1, maven.calls.get())
        assertEquals(1, registry.stats().builds, "one Maven process, coordinated")
        assertEquals(0, registry.stats().speculativeBuilds, "both callers were real requests")
        assertEquals(1, registry.stats().reused)
    }
}
