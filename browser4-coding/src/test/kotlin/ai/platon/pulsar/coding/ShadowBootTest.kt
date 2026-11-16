package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Decision logic and bundle discovery for the boot probe (isolation plan §D9).
 *
 * The probe itself starts a real JVM and is verified against a live checkout; a
 * unit test must never boot the backend.
 */
class ShadowBootTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("mode off never probes, whatever changed")
    fun offNeverProbes() {
        assertFalse(ShadowBoot.shouldRun(listOf("browser4-rest/src/main/kotlin/X.kt"), "off"))
    }

    @Test
    @DisplayName("auto probes boot-chain modules only")
    fun autoProbesBootChain() {
        assertTrue(ShadowBoot.shouldRun(listOf("browser4-rest/src/main/kotlin/X.kt"), "auto"))
        assertTrue(ShadowBoot.shouldRun(listOf("browser4-core/browser4-skeleton/src/main/kotlin/X.kt"), "auto"))
        assertTrue(ShadowBoot.shouldRun(listOf("browser4-apps/browser4-bundle/src/main/kotlin/X.kt"), "auto"))
        assertFalse(ShadowBoot.shouldRun(listOf("browser4-plugins/browser4-seo/src/main/kotlin/X.kt"), "auto"))
        assertFalse(ShadowBoot.shouldRun(listOf("docs/config.md"), "auto"))
        assertFalse(ShadowBoot.shouldRun(listOf("tests/foo.kt"), "auto"))
    }

    @Test
    @DisplayName("always probes any code change")
    fun alwaysProbesAnyChange() {
        assertTrue(ShadowBoot.shouldRun(listOf("docs/config.md"), "always"))
        assertFalse(ShadowBoot.shouldRun(emptyList(), "always"))
    }

    @Test
    @DisplayName("an unknown mode stays off instead of guessing")
    fun unknownModeStaysOff() {
        assertFalse(ShadowBoot.shouldRun(listOf("browser4-rest/src/main/kotlin/X.kt"), "sometimes"))
    }

    @Test
    @DisplayName("no assembled bundle means no probe (reported as skipped, merge allowed)")
    fun missingBundleIsSkipped() {
        val outcome = ShadowBoot.verify(
            repoRoot = tempDir,
            worktree = tempDir,
            changedFiles = listOf("browser4-rest/src/main/kotlin/X.kt"),
            mode = "auto",
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.skipped, outcome.reason)
        assertTrue(outcome.reason.contains("no assembled runtime bundle"), outcome.reason)
    }

    @Test
    @DisplayName("mode off short-circuits before touching the filesystem")
    fun offShortCircuits() {
        val outcome = ShadowBoot.verify(
            repoRoot = tempDir,
            worktree = tempDir,
            changedFiles = listOf("browser4-rest/src/main/kotlin/X.kt"),
            mode = "off",
        )
        assertTrue(outcome.ok)
        assertTrue(outcome.skipped)
        assertTrue(outcome.reason.contains("mode=off"), outcome.reason)
    }

    @Test
    @DisplayName("the bundle layout is discovered from _work/<platform>/<bundle>/lib")
    fun discoversBundleLayout() {
        val work = tempDir.resolve("browser4-apps/browser4-bundle/target/runtime-bundle/_work/plat")
        val bundle = work.resolve("bundle")
        Files.createDirectories(work.resolve("app-classes"))
        Files.createDirectories(bundle.resolve("lib"))
        Files.writeString(bundle.resolve("lib/browser4-rest-1.0.jar"), "jar")
        val javaName = if (isWindows()) "java.exe" else "java"
        Files.createDirectories(bundle.resolve("runtime/bin"))
        Files.writeString(bundle.resolve("runtime/bin/$javaName"), "java")

        val found = ShadowBoot.findBundle(tempDir, tempDir)

        assertNotNull(found, "the assembled bundle must be discovered")
        assertEquals(work.resolve("app-classes"), found!!.appClasses)
        assertEquals(bundle.resolve("lib"), found.lib)
        assertNotNull(found.javaExecutable, "the bundled JRE must be found")
    }

    @Test
    @DisplayName("a _work directory without app-classes is not a usable bundle")
    fun incompleteBundleIsIgnored() {
        val work = tempDir.resolve("browser4-apps/browser4-bundle/target/runtime-bundle/_work/plat")
        Files.createDirectories(work.resolve("bundle/lib"))
        // No app-classes next to the bundle dir.

        assertNull(ShadowBoot.findBundle(tempDir, tempDir))
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
