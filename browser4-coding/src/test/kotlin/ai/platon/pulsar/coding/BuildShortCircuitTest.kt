package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * P0-1: a build of unchanged inputs must not start Maven.
 *
 * The property under test is *behavioural* ("a second build does not run mvn"), so every
 * case counts invocations of an injected command runner instead of asserting on a mock.
 * The cache is content-based, which is what makes "an edit outside the coding file system"
 * (`coding.shell`'s `sed`, an external formatter) invalidate it — the historical way a
 * build cache turns into a **stale green**.
 */
@Tag("Fast")
@DisplayName("Build short-circuit (P0-1): content fingerprint + process cache")
class BuildShortCircuitTest {

    @TempDir
    lateinit var tmp: Path

    /** Counts Maven invocations; the only proof that a cache hit skipped the build. */
    private class FakeMaven {
        var calls = 0
        var exitCode = 0
        var timedOut = false
        var stdout = ""
        val commands = mutableListOf<String>()

        val runner: suspend (CodingAgentShell, String, Long, String?) -> ShellResult = { _, command, _, _ ->
            calls++
            commands += command
            ShellResult(
                sessionId = "fake",
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = "",
                durationMs = 1,
                timedOut = timedOut,
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

    private fun shellFor(root: Path) = CodingAgentShell(baseDir = root)

    private fun support(cache: BuildCache, maven: FakeMaven) = MavenBuildSupport(cache = cache, commandRunner = maven.runner)

    private fun build(
        support: MavenBuildSupport,
        shell: CodingAgentShell,
        module: String = "browser4-coding",
        goals: String = "compile",
        skipTests: Boolean = true,
    ) = runBlocking { support.build(shell, module, goals, skipTests) }

    @Test
    @DisplayName("a zero-edit rebuild is served from the cache and starts no Maven")
    fun zeroEditRebuildIsCached() {
        val root = workspace("ws1")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        val first = build(support, shell)
        assertEquals(1, maven.calls, "the first build must run Maven")
        assertFalse(first.cached, "the first build cannot be cached")
        assertTrue(first.success)
        assertNotNull(first.fingerprint, "a cacheable build must report its fingerprint")

        val second = build(support, shell)
        assertEquals(1, maven.calls, "a zero-edit rebuild must NOT start Maven")
        assertTrue(second.cached, "the second build must be answered from the cache")
        assertTrue(second.success, "a cached build keeps the original outcome")
        assertEquals(first.fingerprint, second.fingerprint, "same inputs, same fingerprint")
        assertTrue(second.output.contains("cached"), "output must say so: ${second.output}")
        assertTrue(second.output.contains("no source change since"), "output: ${second.output}")
        assertTrue(second.builtAt > 0L, "a cached answer carries the original build time")
    }

    @Test
    @DisplayName("a coding.write edit invalidates the cache")
    fun writeInvalidates() {
        val root = workspace("ws2")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        build(support, shell)
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Foo.kt"), "fun main() = 42")

        val rebuilt = build(support, shell)
        assertEquals(2, maven.calls, "an edited source must rebuild")
        assertFalse(rebuilt.cached)
    }

    @Test
    @DisplayName("an edit outside the coding file system (coding.shell / formatter) also invalidates")
    fun shellEditInvalidates() {
        val root = workspace("ws3", source = "fun main() = Unit")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        val first = build(support, shell)
        // The historical false-green path: the file changed but nothing told the file system.
        Files.writeString(root.resolve("browser4-coding/src/main/kotlin/Bar.kt"), "class Bar")
        val second = build(support, shell)

        assertEquals(2, maven.calls, "a write the mutation log never saw must still rebuild")
        assertFalse(second.cached)
        assertNotEquals(first.fingerprint, second.fingerprint, "the content digest must change")
    }

    @Test
    @DisplayName("a pom.xml change invalidates the cache")
    fun pomChangeInvalidates() {
        val root = workspace("ws4")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        build(support, shell)
        Files.writeString(
            root.resolve("browser4-coding/pom.xml"),
            "<project><artifactId>browser4-coding</artifactId><version>2</version></project>",
        )
        build(support, shell)
        assertEquals(2, maven.calls, "a pom change must rebuild")
    }

    @Test
    @DisplayName("two workspace roots never share a cache entry")
    fun rootsAreIsolated() {
        val rootA = workspace("wsA")
        val rootB = workspace("wsB", source = "fun main() = 1")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)

        build(support, shellFor(rootA))
        build(support, shellFor(rootB))
        assertEquals(2, maven.calls, "a different root must not hit the first root's entry")

        // Coming back to A with unchanged content is still a hit: isolation, not a flush.
        val backToA = build(support, shellFor(rootA))
        assertEquals(2, maven.calls, "root A is still cached")
        assertTrue(backToA.cached)
    }

    @Test
    @DisplayName("failures and timeouts are never cached")
    fun failuresAreNotCached() {
        val root = workspace("ws5")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        maven.exitCode = 1
        maven.stdout = "e: file:///ws/Foo.kt:1:1 Unresolved reference: bar"
        val failed = build(support, shell)
        assertFalse(failed.success)
        assertFalse(failed.cached)

        maven.exitCode = 1
        build(support, shell)
        assertEquals(2, maven.calls, "a failed build must be retried, not replayed")

        maven.exitCode = 0
        maven.timedOut = true
        val timedOut = build(support, shell)
        assertFalse(timedOut.cached)
        maven.timedOut = false
        val afterTimeout = build(support, shell)
        assertFalse(afterTimeout.cached, "a timeout is not evidence about the sources")
        assertTrue(afterTimeout.success)
    }

    @Test
    @DisplayName("only pure compile builds are cacheable")
    fun onlyCompileIsCacheable() {
        assertTrue(MavenBuildSupport.isCacheableBuild("compile", skipTests = true))
        assertTrue(MavenBuildSupport.isCacheableBuild("  compile  ", skipTests = true))
        assertFalse(MavenBuildSupport.isCacheableBuild("compile", skipTests = false))
        assertFalse(MavenBuildSupport.isCacheableBuild("test", skipTests = true))
        assertFalse(MavenBuildSupport.isCacheableBuild("compile test", skipTests = true))
        assertFalse(MavenBuildSupport.isCacheableBuild("package", skipTests = true))
        assertFalse(MavenBuildSupport.isCacheableBuild("", skipTests = true))
    }

    @Test
    @DisplayName("goals=test always runs Maven, even with an unchanged tree")
    fun testGoalIsNeverShortCircuited() {
        val root = workspace("ws6")
        val maven = FakeMaven()
        val support = support(BuildCache(), maven)
        val shell = shellFor(root)

        build(support, shell)
        build(support, shell, goals = "test", skipTests = false)
        build(support, shell, goals = "test", skipTests = false)
        assertEquals(3, maven.calls, "test goals must never be replayed from the build cache")
    }

    @Test
    @DisplayName("-Dbrowser4.agent.buildCache.enabled=false restores the old behaviour")
    fun escapeHatchDisablesTheCache() {
        val root = workspace("ws7")
        val maven = FakeMaven()
        // A cache that reads the real JVM property, like the shared instance.
        val cache = BuildCache(enabledProvider = BuildCache::enabled)
        val support = support(cache, maven)
        val shell = shellFor(root)

        val key = "browser4.agent.buildCache.enabled"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "false")
            build(support, shell)
            val second = build(support, shell)
            assertEquals(2, maven.calls, "with the cache off every build runs")
            assertFalse(second.cached)
            // Nothing was stored either: turning the cache back on must not replay it.
            System.setProperty(key, "true")
            val third = build(support, shell)
            assertEquals(3, maven.calls, "a disabled cache must not store entries")
            assertFalse(third.cached)
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    @DisplayName("the cache is bounded and a task boundary invalidates one root only")
    fun cacheIsBoundedAndInvalidatable() {
        val cache = BuildCache(maxEntries = 2)
        val rootA = workspace("wsC1")
        val rootB = workspace("wsC2", source = "fun main() = 2")
        val rootC = workspace("wsC3", source = "fun main() = 3")
        val maven = FakeMaven()
        val support = support(cache, maven)

        build(support, shellFor(rootA))
        build(support, shellFor(rootB))
        build(support, shellFor(rootC))
        assertTrue(cache.size <= 2, "the cache must stay bounded, was ${cache.size}")

        cache.invalidate(rootC)
        val hit = build(support, shellFor(rootC))
        assertFalse(hit.cached, "an invalidated root must rebuild")
    }

    @Test
    @DisplayName("a cached build renders as cached, without a Maven log tail")
    fun cachedFormatting() {
        val cached = BuildResult(
            module = "browser4-coding",
            goals = "compile",
            exitCode = 0,
            output = BuildCache.cachedOutput(1_700_000_000_000L),
            diagnostics = emptyList(),
            cached = true,
            fingerprint = "abcdef0123456789",
            builtAt = 1_700_000_000_000L,
        )
        val text = MavenBuildSupport().format(cached)
        assertTrue(text.contains("cached"), text)
        assertTrue(text.contains("abcdef0123456789"), text)
        assertTrue(text.contains("no source change since"), text)
        assertTrue(text.contains("✓ Build succeeded"), text)
        // The non-cached rendering must stay exactly as before (other tools/tests parse it).
        val fresh = cached.copy(cached = false, fingerprint = null, builtAt = 0, output = "")
        assertTrue(MavenBuildSupport().format(fresh).contains("→ exit 0"))
    }

    @Test
    @DisplayName("an unknown workspace root bypasses the cache instead of guessing")
    fun unknownRootIsNotCached() {
        assertNull(SourceDigest.digest(null))
        assertNull(SourceDigest.digest(tmp.resolve("does-not-exist")))
        val fp = MavenBuildSupport.fingerprint(tmp.resolve("nothing-here"), "browser4-coding", "compile", true, "mvn")
        assertNull(fp, "no digest, no fingerprint, no cache")
    }
}
