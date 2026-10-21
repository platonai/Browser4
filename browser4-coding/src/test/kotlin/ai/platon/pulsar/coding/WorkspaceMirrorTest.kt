package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
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
}
