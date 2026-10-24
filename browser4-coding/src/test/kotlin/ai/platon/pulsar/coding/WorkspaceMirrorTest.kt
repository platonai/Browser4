package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Workspace provisioning (self-development runs): the agent workspace must show
 * every reactor module and the build entry files before the first tool call,
 * derived from the checkout's root pom.
 */
class WorkspaceMirrorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun repoFixture(modules: List<String> = listOf("browser4-coding", "browser4-rest", "browser4-plugins")): Path {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        Files.writeString(repo.resolve("ROOT.md"), "# Browser4\n")
        Files.writeString(repo.resolve("VERSION"), "4.14.2-SNAPSHOT\n")
        Files.writeString(repo.resolve("mvnw.cmd"), "@echo off\n")
        Files.createDirectories(repo.resolve(".mvn/wrapper"))
        Files.writeString(repo.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=x\n")
        Files.writeString(
            repo.resolve("pom.xml"),
            """
            <project>
              <modules>
            ${modules.joinToString("\n") { "    <module>$it</module>" }}
              </modules>
            </project>
            """.trimIndent()
        )
        for (module in modules) {
            val dir = Files.createDirectories(repo.resolve(module))
            Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>$module</artifactId></project>")
        }
        return repo
    }

    private fun workspace(): Path {
        val ws = Files.createDirectories(tempDir.resolve("workspace"))
        // A partial mirror: the root markers exist, the modules do not.
        Files.writeString(ws.resolve("ROOT.md"), "# Browser4\n")
        Files.writeString(ws.resolve("pom.xml"), "<project/>")
        return ws
    }

    @Test
    @DisplayName("every reactor module is linked and the build entry files are copied")
    fun mirrorsModulesAndRootFiles() {
        val repo = repoFixture()
        val ws = workspace()

        val report = WorkspaceMirror.ensure(ws, repo)

        assertEquals(repo, report.sourceRepo)
        assertEquals(
            listOf(".mvn", "browser4-coding", "browser4-plugins", "browser4-rest"),
            report.linked.sorted(),
        )
        assertTrue(report.copied.contains("VERSION"), "copied: ${report.copied}")
        assertTrue(report.copied.contains("mvnw.cmd"), "copied: ${report.copied}")
        assertTrue(report.errors.isEmpty(), "errors: ${report.errors}")

        for (module in listOf("browser4-coding", "browser4-rest", "browser4-plugins")) {
            val linked = ws.resolve(module)
            assertTrue(Files.isDirectory(linked), "module link missing: $module")
            assertTrue(Files.isSameFile(linked, repo.resolve(module)), "link must resolve to the checkout: $module")
        }
        assertTrue(Files.isSameFile(ws.resolve(".mvn"), repo.resolve(".mvn")))
        assertEquals("4.14.2-SNAPSHOT\n", Files.readString(ws.resolve("VERSION")))
        // Existing root files are never overwritten.
        assertEquals("<project/>", Files.readString(ws.resolve("pom.xml")))
        assertTrue(WorkspaceMirror.isComplete(ws, repo))
    }

    @Test
    @DisplayName("a second pass is a no-op (idempotent)")
    fun secondPassIsNoOp() {
        val repo = repoFixture()
        val ws = workspace()
        WorkspaceMirror.ensure(ws, repo)

        val again = WorkspaceMirror.ensure(ws, repo)

        assertFalse(again.changed, "nothing new should be linked: ${again.linked} ${again.copied}")
        assertTrue(again.skipped.size >= 3, "skipped: ${again.skipped}")
    }

    @Test
    @DisplayName("a real checkout is never mirrored onto itself")
    fun leavesRealCheckoutAlone() {
        val repo = repoFixture()
        val report = WorkspaceMirror.ensure(repo, repo)
        assertFalse(report.changed)
        assertTrue(report.linked.isEmpty() && report.copied.isEmpty())
    }

    @Test
    @DisplayName("a module declared in the pom but missing on disk is reported, not invented")
    fun reportsMissingModuleSource() {
        val repo = repoFixture()
        Files.delete(repo.resolve("browser4-rest").resolve("pom.xml"))
        Files.delete(repo.resolve("browser4-rest"))
        val ws = workspace()

        val report = WorkspaceMirror.ensure(ws, repo)

        assertTrue(report.errors.any { it.contains("browser4-rest") }, "errors: ${report.errors}")
        assertFalse(Files.exists(ws.resolve("browser4-rest")))
        assertTrue(Files.isDirectory(ws.resolve("browser4-coding")))
    }

    @Test
    @DisplayName("without a source checkout the workspace is left untouched")
    fun noSourceRepo() {
        val ws = workspace()
        val report = WorkspaceMirror.ensure(ws, null)
        assertNull(report.sourceRepo)
        assertFalse(report.changed)
        assertEquals("no source checkout found — workspace left untouched", report.summary())
    }

    @Test
    @DisplayName("reactorModules reads the root pom's <module> entries")
    fun reactorModulesParsed() {
        val repo = repoFixture(listOf("browser4-coding", "browser4-plugins"))
        assertEquals(listOf("browser4-coding", "browser4-plugins"), WorkspaceMirror.reactorModules(repo))
    }

    // ================================================================
    // v0.3 tests — depth sort, real pom fixture, ProcessRunner timeout
    // ================================================================

    @Test
    @DisplayName("v0.3: reactorModules sorts by depth ascending (shallow first)")
    fun reactorModulesDepthSorted() {
        // A realistic pattern: parent module declared AFTER a nested child
        // (which is what triggered the 1-failed noise before v0.3).
        val repo = repoFixture(listOf(
            "browser4-tests/pulsar-tests-common",
            "browser4-plugins",
            "browser4-plugins/browser4-pageinfo",
            "browser4-plugins/browser4-standalone-app",
            "browser4-core",
            "browser4-tests",
        ))
        val result = WorkspaceMirror.reactorModules(repo)
        // Every shallow entry must come before its deep descendant.
        for (shallow in result) {
            for (deep in result) {
                if (deep.startsWith("$shallow/")) {
                    assertTrue(result.indexOf(shallow) < result.indexOf(deep)) {
                        "shallow '$shallow' must come before deep '$deep'"
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("v0.3: mirror with depth-sorted reactor has zero errors for deeply nested fixture")
    fun depthSortedMirrorNoErrors() {
        // Build a fixture where deep modules come BEFORE their parent in the pom.
        val modules = listOf(
            "browser4-tests/pulsar-tests-common",
            "browser4-plugins/browser4-pageinfo",
            "browser4-plugins",
            "browser4-tests",
            "browser4-core",
        )
        val repo = repoFixture(modules)
        val ws = workspace()
        val report = WorkspaceMirror.ensure(ws, repo)
        assertTrue(report.errors.isEmpty(), "depth-sorted mirror should have zero errors, got: ${report.errors}")
        assertTrue(report.unavailable.isEmpty(), "unavailable should be empty, got: ${report.unavailable}")
        // The deepest entry should be readable (parent was created by depth sort).
        assertTrue(Files.exists(ws.resolve("browser4-tests/pulsar-tests-common/pom.xml")),
            "nested child pom should be readable after depth-sorted mirror")
    }

    @Test
    @DisplayName("v0.3: mirror preserves pre-existing unrelated files (partial mirror idempotent)")
    fun partialMirrorPreservesUnrelated() {
        val repo = repoFixture(listOf("browser4-coding", "browser4-rest"))
        val ws = workspace()
        // Pre-create an unrelated file the mirror must not delete.
        Files.writeString(ws.resolve("scratch.txt"), "keep me\n")
        WorkspaceMirror.ensure(ws, repo)
        assertTrue(Files.exists(ws.resolve("scratch.txt")), "unrelated pre-existing file must survive mirroring")
    }

    @Test
    @DisplayName("v0.3: mirror marks unavailable classified-failures when ProcessRunner returns hung subprocess")
    fun mklinkTimeoutClassifiesAsTimeout() {
        // mklink is only executed on the Windows junction path; on other
        // platforms ensure() links symbolically and never consults the
        // process runner, so the injected hung process would go unused.
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("win"),
            "mklink timeout classification requires the Windows junction path",
        )
        // Save default, inject a fake runner whose Process.waitFor(timeout, unit) never returns true, then restore.
        val original = WorkspaceMirror.processRunner
        val fakeProcess = object : Process() {
            override fun waitFor() = 1
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = false
            override fun exitValue() = 1
            override fun destroy() {}
            override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun isAlive() = true
        }
        WorkspaceMirror.processRunner = ProcessRunner { _, _ -> fakeProcess }
        try {
            val repo = repoFixture(listOf("browser4-coding"))
            val ws = workspace()
            val report = WorkspaceMirror.ensure(ws, repo)
            assertTrue(report.unavailable.isNotEmpty(), "expected at least one timeout failure")
            assertTrue(report.unavailable.all { it.kind == MirrorFailureKind.TIMEOUT })
            assertTrue(report.unavailable.first().detail.contains("hung"))
        } finally {
            WorkspaceMirror.processRunner = original
        }
    }

    @Test
    @DisplayName("v0.3: mirror produces errors-only list when ProcessRunner privilege-denies")
    fun mklinkPrivilegeDeniedClassifiesAsPrivilege() {
        // Windows-only: the mklink privilege-denied classification is only
        // reachable via the Windows `cmd /c mklink /J` path (symlink platforms
        // do not consult the injected process runner).
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("win"),
            "mklink privilege classification requires the Windows junction path",
        )
        val original = WorkspaceMirror.processRunner
        val fakeProcess = object : Process() {
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
            override fun exitValue() = 1
            override fun destroy() {}
            override fun getInputStream() = java.io.ByteArrayInputStream("You do not have sufficient privilege".toByteArray())
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun isAlive() = false
        }
        WorkspaceMirror.processRunner = ProcessRunner { _, _ -> fakeProcess }
        try {
            val repo = repoFixture(listOf("browser4-coding"))
            val ws = workspace()
            val report = WorkspaceMirror.ensure(ws, repo)
            assertTrue(report.unavailable.isNotEmpty(), "expected at least one privilege failure")
            assertTrue(report.unavailable.all { it.kind == MirrorFailureKind.PRIVILEGE },
                "all failures should be PRIVILEGE but got: ${report.unavailable.map { it.kind }}")
        } finally {
            WorkspaceMirror.processRunner = original
        }
    }

    @Test
    @DisplayName("v0.3: MirrorReport.isHealthy reflects unavailable list")
    fun mirrorReportHealthyOnlyWhenNoUnavailable() {
        val repo = repoFixture(listOf("browser4-coding", "browser4-rest"))
        val ws = workspace()
        val healthy = WorkspaceMirror.ensure(ws, repo)
        assertTrue(healthy.isHealthy)
        assertEquals(0, healthy.unavailable.size)
    }
}
