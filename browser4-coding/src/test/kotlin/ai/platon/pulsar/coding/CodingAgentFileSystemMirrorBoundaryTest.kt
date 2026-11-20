package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that the file-system boundary, now decided on real paths through [Authorizer], still
 * treats mirrored module junctions as in-scope while refusing genuinely external paths.
 *
 * This is the A3 behavior-equivalence contract: the previous lexical `startsWith(workspaceRoot)`
 * check let module junctions through because their *lexical* path lived in the workspace. The new
 * real-path check must let them through because their *real* target is a mirror root **recorded by
 * the backend** (`<workspace>.mirror-manifest`), never because the workspace happens to hold a link.
 */
class CodingAgentFileSystemMirrorBoundaryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun link(linkPath: Path, target: Path) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", linkPath.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assumeTrue(exit == 0, "mklink /J unavailable: $output")
        } else {
            runCatching { Files.createSymbolicLink(linkPath, target) }
                .onFailure { assumeTrue(false, "symlinks unsupported here: ${it.message}") }
        }
    }

    /** A checkout with the given reactor modules, each a project of its own. */
    private fun checkout(vararg modules: String): Path {
        val root = Files.createDirectories(tempDir.resolve("checkout"))
        Files.writeString(root.resolve("pom.xml"), "<project/>")
        for (module in modules) {
            val dir = Files.createDirectories(root.resolve(module))
            Files.writeString(dir.resolve("pom.xml"), "<project/>")
        }
        return root
    }

    private fun workspace(): Path = Files.createDirectories(tempDir.resolve("workspace"))

    /** What `WorkspaceMirror.ensure` records: the exact link → target pairs it created. */
    private fun recordMirror(workspace: Path, source: Path, vararg links: Pair<String, Path>) {
        MirrorRoots.writeManifest(workspace, source, links.toMap())
    }

    @Test
    @DisplayName("MirrorRoots.derive finds the real target of a recorded module link")
    fun derivesMirrorRoot() {
        val root = checkout("browser4-coding")
        val real = root.resolve("browser4-coding")
        val workspace = workspace()
        link(workspace.resolve("browser4-coding"), real)
        recordMirror(workspace, root, "browser4-coding" to real)

        val roots = MirrorRoots.derive(workspace)

        val realNormalized = real.toRealPath().normalize()
        assertTrue(roots.any { it == realNormalized }, "expected $realNormalized in $roots")
    }

    @Test
    @DisplayName("MirrorRoots.derive finds a nested reactor module link")
    fun derivesGroupedModuleRoot() {
        val root = checkout("browser4-plugins/browser4-testprobe")
        val real = root.resolve("browser4-plugins/browser4-testprobe")
        val workspace = workspace()
        Files.createDirectories(workspace.resolve("browser4-plugins"))
        link(workspace.resolve("browser4-plugins/browser4-testprobe"), real)
        recordMirror(workspace, root, "browser4-plugins/browser4-testprobe" to real)

        val roots = MirrorRoots.derive(workspace)

        val realNormalized = real.toRealPath().normalize()
        assertTrue(roots.any { it == realNormalized }, "expected the grouped module in $roots")
    }

    @Test
    @DisplayName("a module name deeper than a mirror can live is not recorded")
    fun deriveStopsAtTheMirrorDepth() {
        // Unbounded walking is what made the first tool call of a big workspace take minutes; a
        // link below the mirror depth is outside the contract (`WorkspaceMirror` links modules).
        val root = checkout()
        val real = Files.createDirectories(tempDir.resolve("real-module"))
        Files.writeString(real.resolve("pom.xml"), "<project/>")
        val workspace = workspace()
        val deep = Files.createDirectories(workspace.resolve("a/b/c/d/e"))
        link(deep.resolve("mod"), real)
        recordMirror(workspace, root, "a/b/c/d/e/mod" to real)

        val roots = MirrorRoots.derive(workspace)

        val realNormalized = real.toRealPath().normalize()
        assertFalse(roots.any { it == realNormalized }, "a module past the depth limit is ignored: $roots")
    }

    @Test
    @DisplayName("a write through a mirrored module junction is allowed")
    fun writeThroughJunctionAllowed() = runBlocking {
        val root = checkout("browser4-coding")
        val real = root.resolve("browser4-coding")
        val workspace = workspace()
        link(workspace.resolve("browser4-coding"), real)
        recordMirror(workspace, root, "browser4-coding" to real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.writeFile("browser4-coding/hello.txt", "hello")

        assertFalse(result.startsWith("Error:"), "write should succeed: $result")
        val written = Files.readString(real.resolve("hello.txt"))
        assertEquals("hello", written)
    }

    @Test
    @DisplayName("a read through a mirrored module junction is allowed")
    fun readThroughJunctionAllowed() = runBlocking {
        val root = checkout("browser4-coding")
        val real = root.resolve("browser4-coding")
        Files.writeString(real.resolve("note.txt"), "mirror-content")
        val workspace = workspace()
        link(workspace.resolve("browser4-coding"), real)
        recordMirror(workspace, root, "browser4-coding" to real)

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile("browser4-coding/note.txt")

        assertFalse(result.startsWith("Error:"), "read should succeed: $result")
        assertTrue(result.contains("mirror-content"))
    }

    @Test
    @DisplayName("MirrorRoots.sourceRoot is the checkout the mirror recorded")
    fun derivesSourceRoot() {
        // A reactor layout: checkout → browser4-tests → pulsar-tests-common, every level a project.
        val checkout = Files.createDirectories(tempDir.resolve("checkout"))
        Files.writeString(checkout.resolve("pom.xml"), "<project/>")
        val aggregate = Files.createDirectories(checkout.resolve("browser4-tests"))
        Files.writeString(aggregate.resolve("pom.xml"), "<project/>")
        val module = Files.createDirectories(aggregate.resolve("pulsar-tests-common"))
        Files.writeString(module.resolve("pom.xml"), "<project/>")
        val workspace = workspace()
        link(workspace.resolve("pulsar-tests-common"), module)
        recordMirror(workspace, checkout, "pulsar-tests-common" to module)

        assertEquals(checkout.toRealPath(), MirrorRoots.sourceRoot(workspace))
    }

    @Test
    @DisplayName("a module with no checkout above it yields no source root")
    fun noSourceRootWithoutACheckout() {
        val real = Files.createDirectories(tempDir.resolve("plain-module"))
        val workspace = workspace()
        link(workspace.resolve("plain-module"), real)
        recordMirror(workspace, real, "plain-module" to real)

        assertNull(MirrorRoots.sourceRoot(workspace))
    }

    @Test
    @DisplayName("the checkout the workspace mirrors is writable, so a self-development task edits itself")
    fun writeIntoSourceCheckoutAllowed() = runBlocking {
        val checkout = checkout("browser4-coding")
        val module = checkout.resolve("browser4-coding")
        val workspace = workspace()
        link(workspace.resolve("browser4-coding"), module)
        recordMirror(workspace, checkout, "browser4-coding" to module)

        val fs = CodingAgentFileSystem(workspace)
        // The absolute spelling of a checkout path: the mirror only links the module, and `.git`,
        // `bin/`, `cli/` and `docs/` exist at the root only (2026-11-14 run: PATH_DENIED).
        val target = checkout.resolve("NOTES.md")
        val result = fs.writeFile(target.toString(), "self")

        assertFalse(result.startsWith("Error:"), "write should succeed: $result")
        assertEquals("self", Files.readString(target))
    }

    @Test
    @DisplayName("a genuinely external absolute path is still refused")
    fun externalPathRefused() = runBlocking {
        val workspace = workspace()
        val outside = Files.writeString(tempDir.resolve("outside.txt"), "no")

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile(outside.toString())

        assertTrue(result.startsWith("Error:"), "external read must be refused: $result")
    }

    @Test
    @DisplayName("a traversal escaping the workspace is refused")
    fun traversalRefused() = runBlocking {
        val workspace = workspace()

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.readFile("../escape.txt")

        assertTrue(result.startsWith("Error:"), "traversal must be refused: $result")
    }

    @Test
    @DisplayName("a link with no recorded mirror is not a grant, and a write through it is refused")
    fun arbitraryLinkTargetIsNotAGrant() = runBlocking {
        // permission-e2e A17 (2026-11-20): every link target was celebrated as a writable mirror
        // root — the low-integrity backend lowered that folder's mandatory label and the sandboxed
        // child wrote into it. A junction inside the workspace therefore became an escape to any
        // directory the user can write, without the workspace ever naming that directory.
        val outside = Files.createDirectories(tempDir.resolve("plain-folder"))
        val workspace = workspace()
        link(workspace.resolve("link"), outside)

        assertTrue(
            MirrorRoots.derive(workspace).isEmpty(),
            "a link the backend did not record must not become a writable root",
        )

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.writeFile("link/escaped.txt", "escaped")

        assertTrue(result.startsWith("Error:"), "the write through the link must be refused: $result")
        assertFalse(Files.exists(outside.resolve("escaped.txt")), "nothing may be written through the link")
    }

    @Test
    @DisplayName("a recorded module whose link was re-pointed at another checkout is not a grant")
    fun rePointedLinkToAnotherCheckoutIsNotAGrant() = runBlocking {
        // Review S1 (2026-11-20): the manifest names a module of *this* checkout, so a link that
        // resolves to a module of a different checkout — both are "modules of a checkout", which is
        // all the old marker heuristic checked — is a mismatch, not a grant.
        val mine = checkout("browser4-coding")
        val other = Files.createDirectories(tempDir.resolve("other-checkout"))
        Files.writeString(other.resolve("pom.xml"), "<project/>")
        val otherModule = Files.createDirectories(other.resolve("browser4-coding"))
        Files.writeString(otherModule.resolve("pom.xml"), "<project/>")
        val workspace = workspace()
        link(workspace.resolve("browser4-coding"), otherModule)
        recordMirror(workspace, mine, "browser4-coding" to mine.resolve("browser4-coding"))

        assertTrue(MirrorRoots.derive(workspace).isEmpty(), "another checkout's module must not be granted")
        assertNull(MirrorRoots.sourceRoot(workspace), "no valid module means no source grant")

        val fs = CodingAgentFileSystem(workspace)
        val result = fs.writeFile("browser4-coding/escaped.txt", "escaped")
        assertTrue(result.startsWith("Error:"), "the write through the re-pointed link must be refused: $result")
        assertFalse(Files.exists(otherModule.resolve("escaped.txt")), "nothing may be written there")
    }

    @Test
    @DisplayName("the mirror manifest sits outside the workspace, where the task cannot rewrite it")
    fun manifestLivesOutsideTheWorkspace() {
        val workspace = workspace()
        val manifest = MirrorRoots.manifestFile(workspace)

        assertEquals(workspace.toRealPath().parent, manifest.parent, "the manifest must be the workspace's sibling")
        assertFalse(manifest.startsWith(workspace.toRealPath()), "a task-writable manifest would be no boundary")
    }
}
