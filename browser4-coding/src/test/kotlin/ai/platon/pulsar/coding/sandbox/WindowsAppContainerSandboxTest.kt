package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * `windows-appcontainer` — browse mode's read **and** write confinement, and the only backend that
 * can refuse a socket.
 *
 * The three claims a browse task depends on are each a test: an ordinary command runs inside the
 * container, the workspace is the only writable place, and the user profile (credentials included) is
 * not reachable at all. Mounting the container needs no administrator, so the tests run as a normal
 * user.
 */
@DisplayName("windows appcontainer sandbox")
class WindowsAppContainerSandboxTest {

    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private class FakeRunner(private val handler: (List<String>) -> SandboxCommandResult) : SandboxCommandRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult {
            calls += command
            return handler(command)
        }
    }

    private fun spec(workspace: Path) = ProcessSpec(
        command = listOf("cmd.exe", "/c", "echo hi"),
        directory = workspace,
        environment = mapOf("PATH" to "C:\\Windows\\System32"),
    )

    /** A workspace the current user owns, so `icacls /grant` is allowed (see low-il's note). */
    private fun writableWorkspace(name: String): Path {
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", name),
        )
        return Files.createDirectories(root.resolve("ws"))
    }

    @Test
    @DisplayName("each workspace gets its own container identity")
    fun eachWorkspaceGetsItsOwnContainer() {
        // Review S2: one shared container SID granted on N workspaces is one principal allowed into
        // all of them. The identity is per workspace, so a task can only reach its own tree.
        val sandbox = WindowsAppContainerSandbox(markerDir = null)
        val a = sandbox.containerNameFor(tempDir.resolve("ws-a"))
        val b = sandbox.containerNameFor(tempDir.resolve("ws-b"))

        assertNotEquals(a, b, "two tasks must not share one SID")
        assertEquals(a, sandbox.containerNameFor(tempDir.resolve("ws-a")), "a retried task reuses its profile")
        assertTrue(
            a.startsWith("${Win32.APP_CONTAINER_NAME}-"),
            "names stay in the product's namespace: $a",
        )
    }

    @Test
    @DisplayName("release revokes the task's grant and deletes its profile, and only once")
    fun releaseDropsTheTaskIdentity() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val workspace = writableWorkspace("appcontainer-release-${System.nanoTime()}")
        val runner = FakeRunner { SandboxCommandResult(0) }
        val deleted = mutableListOf<String>()
        val sandbox = WindowsAppContainerSandbox(
            runner = runner,
            probe = { null },
            markerDir = null,
            deleteProfile = { name ->
                deleted += name
                true
            },
        )
        try {
            sandbox.prepare(spec(workspace), workspace, allowNetwork = false)
            val expectedName = sandbox.containerNameFor(workspace)

            runner.calls.clear()
            sandbox.release(workspace)

            val revoke = runner.calls.firstOrNull { it.firstOrNull() == "icacls" }
            assertNotNull(revoke, "the grant must be revoked: ${runner.calls}")
            assertEquals("/remove", revoke!![2])
            assertTrue(revoke[3].startsWith("*S-1-15-2-"), "the revoke names the container SID: ${revoke[3]}")
            assertEquals(listOf(expectedName), deleted, "the task profile must be deleted")

            sandbox.release(workspace)
            assertEquals(1, deleted.size, "a second release must be a no-op")
        } finally {
            runCatching { workspace.parent.toFile().deleteRecursively() }
        }
    }


    @Test
    @DisplayName("the container is the one backend that can refuse the network")
    fun containerRefusesTheNetwork() {
        assertEquals("windows-appcontainer", WindowsAppContainerSandbox().id)
        assertTrue(WindowsAppContainerSandbox().enforcesNetwork, "no capability means no egress")
        assertFalse(ExecutionSandboxes.of("windows-low-il").enforcesNetwork)
    }

    @Test
    @DisplayName("the container is opt-in for a mode's chain, and selectable outright")
    fun containerIsOptIn() {
        val previous = System.getProperty(WindowsAppContainerSandbox.PROP_BROWSE_OPT_IN)
        try {
            System.clearProperty(WindowsAppContainerSandbox.PROP_BROWSE_OPT_IN)
            assertFalse(
                WindowsAppContainerSandbox().autoSelectable,
                "the browse chain must not pick a backend that cannot reach browser4-cli yet",
            )
            System.setProperty(WindowsAppContainerSandbox.PROP_BROWSE_OPT_IN, "on")
            assertTrue(WindowsAppContainerSandbox().autoSelectable, "the operator can opt in")
        } finally {
            if (previous == null) System.clearProperty(WindowsAppContainerSandbox.PROP_BROWSE_OPT_IN)
            else System.setProperty(WindowsAppContainerSandbox.PROP_BROWSE_OPT_IN, previous)
        }
    }

    @Test
    @DisplayName("prepare grants the container SID modify on the workspace and nothing else")
    fun grantsTheContainerSid() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val workspace = writableWorkspace("appcontainer-grant-${System.nanoTime()}")
        val runner = FakeRunner { SandboxCommandResult(0) }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = null)
        try {
            val prepared = sandbox.prepare(spec(workspace), workspace, allowNetwork = false)

            val grant = runner.calls.firstOrNull { it.firstOrNull() == "icacls" && it[1] == workspace.toString() }
            assertNotNull(grant, "the workspace must be granted: ${runner.calls}")
            assertEquals("/grant", grant!![2])
            assertTrue(
                grant[3].startsWith("*S-1-15-2-"),
                "the grant must name the container SID in `*SID` form: ${grant[3]}",
            )
            assertTrue(grant[3].endsWith(":(OI)(CI)(M)"), "deep modify rights: ${grant[3]}")
            // Only the workspace (and `--add-dir` roots) may be granted: granting a folder outside it
            // — the data directory, the CLI install tree — also needs a traverse grant on every
            // ancestor, and those ancestors are owned by the system, not by the user.
            assertEquals(1, runner.calls.count { it.firstOrNull() == "icacls" }, "one grant for one workspace")
            assertTrue(
                runner.calls.none { it.getOrNull(1)?.contains(".ssh", ignoreCase = true) == true },
                "a credential store must never be granted: ${runner.calls.filter { it[1].contains("ssh") }}",
            )
            assertNotNull(prepared.sandboxLauncher, "the container backend owns the launch")
            assertTrue(
                prepared.environment.getValue("USERPROFILE").startsWith(workspace.toString()),
                "the child's home must be inside the granted tree: ${prepared.environment["USERPROFILE"]}",
            )
        } finally {
            runCatching { workspace.parent.toFile().deleteRecursively() }
        }
    }

    @Test
    @DisplayName("off Windows the backend refuses to prepare instead of degrading")
    fun refusesOffWindows() {
        val previous = System.getProperty("os.name")
        System.setProperty("os.name", "Linux")
        try {
            val runner = FakeRunner { SandboxCommandResult(0) }
            val error = assertThrows(SandboxUnavailableException::class.java) {
                WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = null)
                    .prepare(spec(tempDir), tempDir, allowNetwork = false)
            }
            assertTrue(error.message!!.contains(WindowsAppContainerSandbox.SANDBOX_ID), error.message!!)
            assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
        } finally {
            if (previous == null) System.clearProperty("os.name") else System.setProperty("os.name", previous)
        }
    }

    @Test
    @DisplayName("a failed probe is a hard failure, never an unconfined launch")
    fun failedProbeIsHardFailure() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val workspace = writableWorkspace("appcontainer-probe-${System.nanoTime()}")
        val runner = FakeRunner { SandboxCommandResult(0) }
        try {
            val error = assertThrows(SandboxUnavailableException::class.java) {
                WindowsAppContainerSandbox(
                    runner = runner,
                    probe = { "a write OUTSIDE the workspace succeeded" },
                    markerDir = null,
                ).prepare(spec(workspace), workspace, allowNetwork = false)
            }
            assertTrue(error.message!!.contains("appcontainer probe failed"), error.message!!)
        } finally {
            runCatching { workspace.parent.toFile().deleteRecursively() }
        }
    }

    @Test
    @DisplayName("a real container runs a command, confines writes, and cannot read the profile")
    fun realContainerIsConfined() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val root = Path.of(System.getProperty("user.home"), ".browser4", "tmp", "appcontainer-real-${System.nanoTime()}")
        val workspace = Files.createDirectories(root.resolve("ws"))
        val sandbox = WindowsAppContainerSandbox(markerDir = null)
        try {
            // prepare() runs the three-part probe and throws when the boundary does not hold.
            sandbox.prepare(spec(workspace), workspace, allowNetwork = false)

            val inside = sandbox.runInContainer(workspace, "echo b4> inside-probe.txt")
            assertEquals(0, inside.first, "a command inside the container must run: ${inside.second}")
            assertTrue(Files.exists(workspace.resolve("inside-probe.txt")), "the workspace write must land")

            val systemTemp = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "Temp")
            if (Files.isDirectory(systemTemp)) {
                val outside = sandbox.runInContainer(systemTemp, "echo b4> b4outside.txt")
                assertTrue(outside.first != 0, "a write under %SystemRoot%\\Temp must be refused: ${outside.second}")
                assertFalse(Files.exists(systemTemp.resolve("b4outside.txt")))
            }

            // Nothing of the user profile is reachable — that is the browse goal, and it is also why
            // `browser4-cli` (which lives under the profile) cannot run inside the container yet.

            val credentials = sandbox.runInContainer(workspace, "dir %B4_SANDBOX_PROBE_HOME%\\.ssh",
                mapOf("B4_SANDBOX_PROBE_HOME" to System.getProperty("user.home")))
            assertTrue(credentials.first != 0, "the credential store must stay unreachable: ${credentials.second}")
        } finally {
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * Plan P3-6 for browse mode: the backend's own file mutations are performed by a container child.
     *
     * This is also the only place that proves `powershell.exe` — the bridge — starts inside the
     * container. It has to: the browse chain may select this backend through
     * `-Dbrowser4.agent.sandbox.browse=on`, and a `coding.write` that cannot start would make browse
     * tasks unusable the moment an operator turns the container on.
     */
    @Test
    @DisplayName("the container child performs the file mutations and still cannot reach the profile")
    fun realContainerExecutesFileMutations() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val root = Path.of(System.getProperty("user.home"), ".browser4", "tmp", "appcontainer-fs-${System.nanoTime()}")
        val workspace = Files.createDirectories(root.resolve("ws"))
        val ops = SandboxedFileOps.forSandbox(WindowsAppContainerSandbox(markerDir = null), workspace)
        assertNotNull(ops, "the container backend must offer the file bridge")
        try {
            ops!!.write(workspace.resolve("notes/a.md"), "b4".toByteArray())
            assertEquals("b4", Files.readString(workspace.resolve("notes/a.md")), "the container write must land")

            ops.mkdirs(workspace.resolve("dir/sub"))
            assertTrue(Files.isDirectory(workspace.resolve("dir/sub")), "mkdir must land inside the container")

            // The profile is outside the granted tree, so the OS refuses a write the policy layer
            // would refuse too — but only one of the two is a boundary.
            val outside = Path.of(System.getProperty("user.home"), ".b4container-escape.txt")
            val error = assertThrows(IOException::class.java) {
                ops.write(outside, "x".toByteArray())
            }
            assertTrue(error.message!!.contains(WindowsAppContainerSandbox.SANDBOX_ID), error.message!!)
            assertFalse(Files.exists(outside), "the container SID must not reach the profile")
        } finally {
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    /**
     * The two facts the `b4.run`-inside-the-container decision (design §16.5 item 2, §14.2) rests on.
     *
     * **No loopback.** A container without capabilities is network-isolated from the host's
     * `127.0.0.1` as well as from the internet, so a `browser4-cli` running *inside* the container
     * cannot reach the backend that drives the browser — no matter how the binary gets there. Three
     * things keep this from passing for the wrong reason: the JVM's own server answers the host
     * first, `curl` is proven to run **inside** the container before it is used as the probe, and the
     * exit code has to be curl's "could not connect"/"timed out", not "command not found".
     *
     * **A staged binary runs.** The other half of every option is "can the container execute a copy
     * staged in the granted workspace?" — it must, or none of the three options in §14.2 can work.
     */
    @Test
    @DisplayName("a container has no egress and no host loopback, but runs a binary staged in the workspace")
    fun realContainerNetworkAndStagedBinary() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val root = Path.of(System.getProperty("user.home"), ".browser4", "tmp", "appcontainer-net-${System.nanoTime()}")
        val workspace = Files.createDirectories(root.resolve("ws"))
        val sandbox = WindowsAppContainerSandbox(markerDir = null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/reach") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            // prepare() grants the workspace and runs the three-part probe.
            sandbox.prepare(spec(workspace), workspace, allowNetwork = false)
            val url = "http://127.0.0.1:${server.address.port}/reach"
            // Sanity: the host reaches it, so "refused" below is isolation, not a broken probe.
            assertEquals("ok", URL(url).readText(), "the probe server must answer the host")

            // `C:\Windows\System32` is readable to the container, so the probe tool itself must work —
            // otherwise a "not recognized" exit code would look exactly like a refused connection.
            val available = sandbox.runInContainer(workspace, "curl.exe --version")
            assertEquals(0, available.first, "curl must run inside the container: ${available.second}")
            assertTrue(available.second.contains("curl"), available.second)

            val loopback = sandbox.runInContainer(workspace, "curl.exe -s -m 5 -o NUL $url")
            assertTrue(
                loopback.first in CURL_NO_EGRESS,
                "the container must be refused the host loopback (6=could not resolve, 7=could not connect, 28=timed out): " +
                    "exit=${loopback.first} out=${loopback.second}",
            )

            val internet = sandbox.runInContainer(workspace, "curl.exe -s -m 5 -o NUL http://example.com/")
            assertTrue(
                internet.first in CURL_NO_EGRESS,
                "the container must be refused the internet: exit=${internet.first} out=${internet.second}",
            )

            // A candidate executable staged into the granted tree: the container has to be able to
            // run it for any "copy the CLI into the workspace" option to be viable at all.
            val staged = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "curl.exe")
            if (Files.isRegularFile(staged)) {
                Files.copy(staged, workspace.resolve("staged-curl.exe"))
                val run = sandbox.runInContainer(workspace, "staged-curl.exe --version")
                assertEquals(0, run.first, "a workspace-staged binary must run in the container: ${run.second}")
                assertTrue(run.second.contains("curl"), "it must be the staged binary: ${run.second}")
            }
        } finally {
            server.stop(0)
            runCatching { root.toFile().deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // G9: a recorded grant must be revocable, or it outlives the task
    // ------------------------------------------------------------------

    /** Plant a grant marker exactly as `grant` writes one, aged by [ageHours]. */
    private fun plantGrant(
        sandbox: WindowsAppContainerSandbox,
        path: Path,
        content: String,
        ageHours: Long = 24,
    ) {
        val marker = requireNotNull(sandbox.markerFile(path, "grant")) {
            "the sandbox must be constructed with a markerDir for this test"
        }
        Files.createDirectories(marker.parent)
        Files.writeString(marker, content)
        Files.setLastModifiedTime(
            marker,
            java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - java.time.Duration.ofHours(ageHours).toMillis(),
            ),
        )
    }

    @Test
    @DisplayName("a task that starts later does not revoke a grant another task is still using")
    fun laterPrepareDoesNotRevokeLiveGrants() {
        assumeTrue(isWindows, "appcontainers only exist on Windows")
        val markers = Files.createDirectories(tempDir.resolve("markers-live"))
        val first = writableWorkspace("appcontainer-live-${System.nanoTime()}")
        val runner = FakeRunner { SandboxCommandResult(0) }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = markers)
        // The first prepare of a backend is the only one allowed to clean up: nothing is running yet.
        sandbox.prepare(spec(first), first, allowNetwork = false)

        // A grant of an earlier task that is still running, older than the retention window.
        val live = Files.createDirectories(tempDir.resolve("live-toolchain"))
        plantGrant(sandbox, live, "$PACKAGE_SID|$live")
        val second = writableWorkspace("appcontainer-later-${System.nanoTime()}")

        sandbox.prepare(spec(second), second, allowNetwork = false)

        assertFalse(
            runner.calls.any { it.contains("/remove") },
            "a second task must never revoke anything: ${runner.calls}",
        )
        assertTrue(
            Files.exists(sandbox.markerFile(live, "grant")!!),
            "the grant a live task is using keeps its marker",
        )
    }

    @Test
    @DisplayName("a stale recorded grant is revoked with the SID the marker recorded")
    fun revokesStaleRecordedGrant() {
        val markers = Files.createDirectories(tempDir.resolve("markers"))
        val stale = Files.createDirectories(tempDir.resolve("stale-toolchain"))
        val runner = FakeRunner { SandboxCommandResult(0) }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = markers)
        plantGrant(sandbox, stale, "$PACKAGE_SID|$stale")

        val revoked = sandbox.revokeRecordedGrants(retention = java.time.Duration.ofHours(1))

        assertEquals(listOf(stale.toAbsolutePath().normalize()), revoked)
        assertEquals(
            listOf(listOf("icacls", stale.toString(), "/remove", "*$PACKAGE_SID")),
            runner.calls,
            "the removal names the recorded SID, not a freshly derived one",
        )
        assertFalse(Files.exists(sandbox.markerFile(stale, "grant")!!), "the marker goes with the grant")
        assertEquals(revoked, sandbox.revokedGrants(), "the cleanup is observable")
    }

    @Test
    @DisplayName("a grant the task still needs, or one that is still fresh, is never revoked")
    fun keepsNeededAndFreshGrants() {
        val markers = Files.createDirectories(tempDir.resolve("markers"))
        val needed = Files.createDirectories(tempDir.resolve("workspace"))
        val fresh = Files.createDirectories(tempDir.resolve("fresh-toolchain"))
        val runner = FakeRunner { SandboxCommandResult(0) }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = markers)
        plantGrant(sandbox, needed, "$PACKAGE_SID|$needed")
        plantGrant(sandbox, fresh, "$PACKAGE_SID|$fresh", ageHours = 0)

        val revoked = sandbox.revokeRecordedGrants(keep = listOf(needed), retention = java.time.Duration.ofHours(1))

        assertTrue(revoked.isEmpty(), "nothing qualified: $revoked")
        assertTrue(runner.calls.isEmpty(), "no icacls at all: ${runner.calls}")
        assertTrue(Files.exists(sandbox.markerFile(needed, "grant")!!), "the task's own root keeps its grant")
        assertTrue(Files.exists(sandbox.markerFile(fresh, "grant")!!), "a live task keeps its grant")
    }

    @Test
    @DisplayName("a legacy path-only marker is attributed through the directory's own ACL")
    fun legacyMarkerUsesTheDirectoryAcl() {
        val markers = Files.createDirectories(tempDir.resolve("markers"))
        val stale = Files.createDirectories(tempDir.resolve("legacy-toolchain"))
        val runner = FakeRunner { command ->
            // The ACL read (2 argv elements) reports the entry; the removal (4) succeeds.
            if (command.size == 2) SandboxCommandResult(0, "$PACKAGE_SID:(OI)(CI)(RX)") else SandboxCommandResult(0)
        }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = markers)
        plantGrant(sandbox, stale, stale.toString())

        val revoked = sandbox.revokeRecordedGrants(retention = java.time.Duration.ofHours(1))

        assertEquals(listOf(stale.toAbsolutePath().normalize()), revoked)
        assertEquals(2, runner.calls.size, "first the ACL read, then the removal: ${runner.calls}")
        assertEquals(listOf("icacls", stale.toString(), "/remove", "*$PACKAGE_SID"), runner.calls[1])
    }

    @Test
    @DisplayName("an unattributable legacy marker is dropped instead of guessing an ACL edit")
    fun unattributableLegacyMarkerIsDropped() {
        val markers = Files.createDirectories(tempDir.resolve("markers"))
        val stale = Files.createDirectories(tempDir.resolve("ambiguous-toolchain"))
        val otherSid = "S-1-15-2-9-9-9-9-9-9-9-9"
        val runner = FakeRunner { command ->
            if (command.size == 2) SandboxCommandResult(0, "$PACKAGE_SID:(OI)(CI)(RX)\n$otherSid:(OI)(CI)(M)")
            else SandboxCommandResult(0)
        }
        val sandbox = WindowsAppContainerSandbox(runner = runner, probe = { null }, markerDir = markers)
        plantGrant(sandbox, stale, stale.toString())

        val revoked = sandbox.revokeRecordedGrants(retention = java.time.Duration.ofHours(1))

        assertTrue(revoked.isEmpty(), "two candidate SIDs must not be guessed between: $revoked")
        assertEquals(1, runner.calls.size, "only the ACL read happened: ${runner.calls}")
        assertFalse(
            Files.exists(sandbox.markerFile(stale, "grant")!!),
            "the marker is dropped so the next grant rewrites it with a SID",
        )
    }

    private companion object {
        /** curl could not resolve, connect or get an answer: it tried, and the network refused it. */
        private val CURL_NO_EGRESS = setOf(6, 7, 28)

        /** A concrete AppContainer package SID — what a grant writes, and what G9 has to clean up. */
        private const val PACKAGE_SID =
            "S-1-15-2-740000081-2787761786-2739701453-4027781610-3709442335-2178988983-1814727619"
    }
}