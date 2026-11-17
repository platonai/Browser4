package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private companion object {
        /** curl could not resolve, connect or get an answer: it tried, and the network refused it. */
        private val CURL_NO_EGRESS = setOf(6, 7, 28)
    }
}
