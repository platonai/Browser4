package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.AgentMode
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingComponents
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan P3-6 — the write side of the file tools, executed inside the OS boundary.
 *
 * Two questions, two halves of this class:
 *  1. **Wiring** (no OS needed): does every mutating file tool actually go through
 *     [SandboxFileOps], and does a refusal become a tool error instead of an in-process write? The
 *     seam is only worth anything if a broken boundary fails closed.
 *  2. **Enforcement** (Windows): does a real Low-integrity (and a real AppContainer) child perform
 *     the mutation *and still refuse* one outside the workspace? A bridge that merely works inside
 *     the workspace would prove nothing — `off` already does that.
 */
@DisplayName("sandboxed file operations")
class SandboxFileOpsTest {

    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    /** A backend that declares (or denies) the file-operation capability, with no OS behaviour. */
    private class FakeBoundary(
        override val id: String = "fake-boundary",
        override val executesFileOps: Boolean = true,
        private val onPrepare: (ProcessSpec) -> ProcessSpec = { it },
    ) : ExecutionSandbox {
        var prepares: Int = 0
            private set

        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec {
            prepares++
            return onPrepare(spec)
        }
    }

    /** Records the operations it is asked to perform, like the bridge would, without a sandbox. */
    private class RecordingOps(private val failure: IOException? = null) : SandboxFileOps {
        val calls = mutableListOf<String>()

        override val backendId: String = "recording"

        override fun write(target: Path, bytes: ByteArray) {
            calls += "write:${target.fileName}:${bytes.size}"
            failure?.let { throw it }
            Files.createDirectories(target.toAbsolutePath().parent)
            Files.write(target, bytes)
        }

        override fun mkdirs(target: Path) {
            calls += "mkdir:${target.fileName}"
            Files.createDirectories(target)
        }

        override fun delete(target: Path, recursive: Boolean) {
            calls += "delete:${target.fileName}:$recursive"
            target.toFile().deleteRecursively()
        }

        override fun copy(source: Path, destination: Path, recursive: Boolean) {
            calls += "copy:${source.fileName}->${destination.fileName}:$recursive"
            Files.copy(source, destination)
        }

        override fun move(source: Path, destination: Path) {
            calls += "move:${source.fileName}->${destination.fileName}"
            Files.move(source, destination)
        }
    }

    private fun fileSystem(root: Path, ops: SandboxFileOps?, persistSnapshots: Boolean = false) =
        CodingAgentFileSystem(
            workspaceRoot = root,
            persistSnapshots = persistSnapshots,
            mutationOpsProvider = { ops },
        )

    /** Markers the Low-integrity backend records when it lowers a label: `deep|path` per file. */
    private fun lowIntegrityLabels(): Map<Path, String> {
        val dir = SandboxDefaults.defaultDataDir().resolve("sandbox").resolve("low-il")
        if (!Files.isDirectory(dir)) return emptyMap()
        return Files.list(dir).use { stream ->
            stream.toList().associateWith { runCatching { Files.readString(it) }.getOrDefault("") }
        }
    }

    /**
     * Puts the mandatory labels a test created back to Medium and forgets their markers.
     *
     * The production backend labels the build caches (`~/.m2`, `~/.cache`) the first time a coding
     * task resolves to it; a test that exercises that wiring must not leave the developer's machine
     * relabelled, and must not leave a marker behind either — a marker without the label would make
     * the next real run skip the walk and then fail its own writes.
     */
    private fun restoreLabels(created: Map<Path, String>) {
        for ((marker, content) in created) {
            val deep = content.substringBefore('|') == "true"
            val target = content.substringAfter('|', "")
            if (target.isNotBlank() && Files.exists(Path.of(target))) {
                SystemSandboxCommandRunner.run(
                    listOf("icacls", target, "/setintegritylevel", if (deep) "(OI)(CI)Medium" else "Medium"),
                    120,
                )
            }
            runCatching { Files.deleteIfExists(marker) }
        }
    }

    // ==================== wiring ====================

    @Test
    @DisplayName("the bridge exists only for a backend that can execute file ops inside its boundary")
    fun testBridgeOnlyForCapableBackends() {
        assertNull(
            SandboxedFileOps.forSandbox(OffSandbox, tempDir),
            "no boundary must keep the in-process path rather than pretend to have one",
        )
        assertNull(
            SandboxedFileOps.forSandbox(FakeBoundary(executesFileOps = false), tempDir),
            "a backend that never claimed the capability must not be given the channel",
        )
        val bridge = SandboxedFileOps.forSandbox(FakeBoundary(id = "fake-boundary"), tempDir)
        assertNotNull(bridge)
        assertEquals("fake-boundary", bridge!!.backendId)

        // The capability itself: the two token/container backends own the channel, and the account
        // backend keeps the historical in-process path (its provisioning was never designed around
        // it, and `coding.permissions` reports it as "policy layer only").
        assertTrue(WindowsLowIntegritySandbox().executesFileOps, "the Low-integrity child must do the writes")
        assertTrue(WindowsAppContainerSandbox().executesFileOps, "the container child must do the writes")
        assertFalse(WindowsAclSandbox().executesFileOps, "the account backend keeps the in-process path")
        assertFalse(OffSandbox.executesFileOps, "no backend means no boundary")
    }

    @Test
    @DisplayName("every mutating tool hands the change to the boundary instead of the backend process")
    fun testMutationsGoThroughTheBoundary() = runBlocking {
        val root = Files.createDirectories(tempDir.resolve("ws"))
        val ops = RecordingOps()
        val fs = fileSystem(root, ops)

        assertTrue(fs.writeFile("a.txt", "hi").startsWith("✓"))
        assertTrue(fs.mkdir("dir/sub").startsWith("✓"))
        assertTrue(fs.appendFile("a.txt", "!").startsWith("✓"))
        assertTrue(fs.replaceInFile("a.txt", "hi", "ho").startsWith("✓"))
        assertTrue(fs.copy("a.txt", "b.txt").startsWith("✓"))
        assertTrue(fs.move("b.txt", "c.txt").startsWith("✓"))
        assertTrue(fs.delete("c.txt").startsWith("✓"))

        assertEquals(
            listOf(
                "write:a.txt:2",
                "mkdir:sub",
                "write:a.txt:3",
                "write:a.txt:3",
                "copy:a.txt->b.txt:false",
                "move:b.txt->c.txt",
                "delete:c.txt:false",
            ),
            ops.calls,
            "each mutation must reach the boundary exactly once",
        )
        assertEquals("ho!", Files.readString(root.resolve("a.txt")))
    }

    @Test
    @DisplayName("a refused mutation is a tool error and leaves the file untouched")
    fun testRefusedMutationFailsClosed() = runBlocking {
        val root = Files.createDirectories(tempDir.resolve("ws-refused"))
        val fs = fileSystem(root, RecordingOps(IOException("Access is denied.")))

        val refused = fs.writeFile("a.txt", "hi")

        assertTrue(refused.startsWith("Error:"), refused)
        assertTrue(refused.contains("Access is denied."), refused)
        assertFalse(Files.exists(root.resolve("a.txt")), "a refused write must not create the file")
    }

    @Test
    @DisplayName("a boundary that cannot be resolved is reported, never replaced by an unconfined write")
    fun testProviderFailureFailsClosed() = runBlocking {
        val root = Files.createDirectories(tempDir.resolve("ws-provider"))
        val fs = CodingAgentFileSystem(
            workspaceRoot = root,
            mutationOpsProvider = {
                throw SandboxUnavailableException(
                    WindowsLowIntegritySandbox.SANDBOX_ID,
                    "Windows sandbox unavailable: low-integrity probe failed",
                )
            },
        )

        val refused = fs.writeFile("a.txt", "hi")

        assertTrue(refused.startsWith("Error:"), refused)
        assertTrue(refused.contains("low-integrity probe failed"), refused)
        assertFalse(Files.exists(root.resolve("a.txt")), "no unconfined fallback")
    }

    @Test
    @DisplayName("snapshot bookkeeping stays in-process: it is not an agent-chosen path")
    fun testBookkeepingStaysInProcess() = runBlocking {
        val root = Files.createDirectories(tempDir.resolve("ws-snapshots"))
        val ops = RecordingOps()
        val fs = fileSystem(root, ops, persistSnapshots = true)

        assertTrue(fs.writeFile("a.txt", "hi").startsWith("✓"))

        assertEquals(listOf("write:a.txt:2"), ops.calls, "only the content write crosses the boundary")
        val snapshots = root.resolve(".browser4").resolve("coding-snapshots")
        assertTrue(Files.isDirectory(snapshots), "the persisted snapshot must be written by the backend")
    }

    // ==================== enforcement ====================

    /**
     * The bridge stages payloads and writes its script with the backend's own (Medium) rights, so a
     * junction at `.b4scratch` used to redirect both outside the workspace — a write the boundary is
     * supposed to make impossible, performed by the backend itself.
     */
    @Test
    @DisplayName("a scratch junction cannot redirect the bridge outside the workspace")
    fun bridgeJunctionIsRefused() {
        assumeTrue(isWindows, "junctions only exist on Windows")
        val workspace = Files.createDirectories(tempDir.resolve("ws-link"))
        val outside = Files.createDirectories(tempDir.resolve("outside-link"))
        createJunction(workspace.resolve(".b4scratch"), outside)
        try {
            val ops = SandboxedFileOps(FakeBoundary(), workspace)

            val error = assertThrows(IOException::class.java) {
                ops.write(workspace.resolve("a.txt"), "x".toByteArray())
            }

            assertTrue(error.message!!.contains("bridge"), error.message!!)
            assertFalse(
                Files.exists(outside.resolve("fs-bridge")),
                "the bridge must not be created outside the workspace",
            )
        } finally {
            val link = workspace.resolve(".b4scratch")
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) link.toFile().delete()
        }
    }

    /** `mklink /J` needs no elevation; `Files.createSymbolicLink` does. */
    private fun createJunction(link: Path, target: Path) {
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), "mklink /J failed: $output")
    }

    /**
     * The whole point of P3-6: the child that performs the mutation runs under the Low-integrity
     * token, so the workspace label — not the path guard — is what keeps it inside.
     */
    @Test
    @DisplayName("a real low-integrity child writes the workspace and is refused outside it")
    fun testRealLowIntegrityBridgeIsConfined() {
        assumeTrue(isWindows, "Windows tokens only")
        // WRITE_OWNER is needed to lower an integrity label, so the tree must be user-owned rather
        // than %TEMP% (which inherits Modify-only from a shared directory) — same as the low-il tests.
        // The space in the name is deliberate: the bridge passes paths as ordinary argv, and the two
        // quoting rules that cannot be layered (`cmd /c` + a payload) is the trap this design avoids.
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "low-il fs-${System.nanoTime()}"),
        )
        val workspace = Files.createDirectories(root.resolve("ws"))
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
        )
        val ops = SandboxedFileOps.forSandbox(sandbox, workspace)
        assertNotNull(ops)
        try {
            // Exact bytes, CRLF and non-ASCII included: the payload is staged as a file, so nothing
            // about the content is decided by a command line or a console code page.
            val content = "package demo\r\n// ünïcode – ok\n"
            ops!!.write(workspace.resolve("src/main/kotlin/demo/A.kt"), content.toByteArray())
            assertEquals(content, Files.readString(workspace.resolve("src/main/kotlin/demo/A.kt")))

            ops.mkdirs(workspace.resolve("dir/sub"))
            assertTrue(Files.isDirectory(workspace.resolve("dir/sub")), "mkdir must land")

            ops.write(workspace.resolve("dir/a.txt"), "a".toByteArray())
            ops.copy(workspace.resolve("dir/a.txt"), workspace.resolve("dir/b.txt"), recursive = false)
            assertEquals("a", Files.readString(workspace.resolve("dir/b.txt")), "copy must land")

            ops.move(workspace.resolve("dir/b.txt"), workspace.resolve("dir/c.txt"))
            assertFalse(Files.exists(workspace.resolve("dir/b.txt")), "move must remove the source")
            assertEquals("a", Files.readString(workspace.resolve("dir/c.txt")), "move must land")

            ops.delete(workspace.resolve("dir/c.txt"), recursive = false)
            assertFalse(Files.exists(workspace.resolve("dir/c.txt")), "delete must land")

            val outside = root.resolve("outside.txt")
            val error = assertThrows(IOException::class.java) {
                ops.write(outside, "x".toByteArray())
            }
            assertTrue(error.message!!.contains(WindowsLowIntegritySandbox.SANDBOX_ID), error.message!!)
            assertFalse(Files.exists(outside), "the OS boundary, not the string layer, must refuse this")
        } finally {
            // Put the labels back so the temporary tree can be deleted.
            SystemSandboxCommandRunner.run(
                listOf("icacls", root.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
                30,
            )
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * The production wiring, not just the bridge: a coding task's policy must resolve to a backend
     * and `CodingComponents.fs` must hand it the seam. Without this, the bridge could pass its own
     * tests while `coding.write` kept writing from the backend process.
     */
    @Test
    @DisplayName("a coding task's file system is wired to the resolved backend end to end")
    fun testProductionWiringSendsWritesThroughTheBoundary() = runBlocking {
        assumeTrue(isWindows, "the resolving backends are Windows-only")
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "wired-fs-${System.nanoTime()}"),
        )
        val workspace = Files.createDirectories(root.resolve("ws"))
        val policy = AgentAccessPolicy(mode = AgentMode.CODING)
        val sandbox = CodingComponents.sandbox(policy)
        assumeTrue(
            sandbox.executesFileOps,
            "this installation resolves no file-operation backend (${sandbox.id})",
        )
        val fs = CodingComponents.fs(workspace, policy)
        // Labels the sandbox lowers while preparing this workspace (caches included); restored below.
        val labelsBefore = lowIntegrityLabels()
        try {
            assertTrue(fs.writeFile("a.txt", "hi").startsWith("✓"), "the wired write must succeed")
            assertEquals("hi", Files.readString(workspace.resolve("a.txt")))

            val refused = fs.writeFile(root.resolve("outside.txt").toString(), "x")
            assertTrue(refused.startsWith("Error:"), refused)
            assertFalse(Files.exists(root.resolve("outside.txt")), "an outside write must not happen")
        } finally {
            SystemSandboxCommandRunner.run(
                listOf("icacls", root.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
                30,
            )
            runCatching { root.toFile().deleteRecursively() }
            restoreLabels(lowIntegrityLabels().filterKeys { it !in labelsBefore.keys })
        }
    }
}
