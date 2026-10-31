package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Plan P1-1 — the Windows sandbox account bootstrap.
 *
 * The account, the ACL grant and the credential are all provisioned out of band, so these tests do
 * not touch a real Windows account: `net` / `icacls` are replaced by [FakeRunner]. What is pinned is
 * the contract that matters — any missing piece is a [SandboxUnavailableException], never a silent
 * fallback to an unwrapped launch.
 */
@DisplayName("windows sandbox bootstrap")
class WindowsSandboxTest {

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

    private fun bootstrap(
        runner: SandboxCommandRunner,
        credentials: SandboxCredentialSource = { charArrayOf('s', 'e', 'c', 'r', 'e', 't') },
    ) = WindowsSandboxBootstrap(accountName = "b4-agent", runner = runner, credentials = credentials)

    private fun workspace(): Path = Files.createDirectories(tempDir.resolve("ws"))

    @Test
    @DisplayName("a missing account is a hard failure, and the grant is not attempted")
    fun missingAccountIsHardFailure() {
        assumeTrue(isWindows, "net user only exists on Windows")
        val runner = FakeRunner { command ->
            if (command.first() == "net") SandboxCommandResult(2, "The user name could not be found.")
            else SandboxCommandResult(0)
        }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner).provision(workspace())
        }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("b4-agent"), error.message!!)
        assertTrue(error.message!!.contains("net user"), error.message!!)
        assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
        assertEquals(1, runner.calls.size, "an unusable account stops the bootstrap before icacls")
    }

    @Test
    @DisplayName("a failing icacls grant is a hard failure")
    fun failingGrantIsHardFailure() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val ws = workspace()
        val runner = FakeRunner { command ->
            when (command.first()) {
                "net" -> SandboxCommandResult(0, "User name b4-agent")
                "icacls" -> SandboxCommandResult(5, "Access is denied.")
                else -> SandboxCommandResult(0)
            }
        }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner).provision(ws)
        }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("write access"), error.message!!)
        assertTrue(error.message!!.contains(ws.toString()), error.message!!)
        assertTrue(error.message!!.contains("icacls"), error.message!!)

        val grant = runner.calls.single { it.first() == "icacls" }
        assertEquals(listOf("icacls", ws.toString(), "/grant", "b4-agent:(OI)(CI)M"), grant)
    }

    @Test
    @DisplayName("an unprovisioned credential is a hard failure before the ACL is changed")
    fun missingCredentialIsHardFailure() {
        assumeTrue(isWindows, "the bootstrap runs on Windows only")
        val runner = FakeRunner { SandboxCommandResult(0, "User name b4-agent") }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner, credentials = { null }).provision(workspace())
        }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("credential"), error.message!!)
        assertTrue(error.message!!.contains("cmdkey"), error.message!!)
        assertTrue(runner.calls.none { it.first() == "icacls" }, "no ACL change without a credential")
    }

    @Test
    @DisplayName("a provisioned account, credential and workspace yield the account")
    fun provisionedAccountIsReturned() {
        assumeTrue(isWindows, "the bootstrap runs on Windows only")
        val ws = workspace()
        val runner = FakeRunner { SandboxCommandResult(0, if (it.first() == "net") "User name b4-agent" else "") }

        val account = bootstrap(runner).provision(ws)

        assertEquals("b4-agent", account.userName)
        assertArrayEquals("secret".toCharArray(), account.password())
        assertEquals(2, runner.calls.size, "net user, then icacls")
        assertEquals(listOf("net", "user", "b4-agent"), runner.calls[0])
        assertEquals(listOf("icacls", ws.toString(), "/grant", "b4-agent:(OI)(CI)M"), runner.calls[1])
        assertTrue(account.toString().contains("<redacted>"), "the password must not be printable: $account")
    }

    @Test
    @DisplayName("a missing workspace fails before any external command runs")
    fun missingWorkspaceIsHardFailure() {
        assumeTrue(isWindows, "the bootstrap runs on Windows only")
        val runner = FakeRunner { SandboxCommandResult(0) }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner).provision(tempDir.resolve("does-not-exist"))
        }

        assertTrue(error.message!!.contains("workspace"), error.message!!)
        assertTrue(runner.calls.isEmpty(), "a missing workspace needs no external command")
    }

    @Test
    @DisplayName("the ACL matrix is workspace write, toolchain read-only, profile deny")
    fun aclMatrixIsAppliedInOrder() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val ws = workspace()
        val module = Files.createDirectories(tempDir.resolve("module"))
        val m2 = Files.createDirectories(tempDir.resolve("m2"))
        val absent = tempDir.resolve("absent-cache")
        val home = Files.createDirectories(tempDir.resolve("home"))
        val runner = FakeRunner { SandboxCommandResult(0, if (it.first() == "net") "User name b4-agent" else "") }

        bootstrap(runner).provision(
            ws,
            writableRoots = listOf(module, ws),
            readOnlyRoots = listOf(m2, absent),
            denyRoots = listOf(home, ws),
        )

        assertEquals(
            listOf(
                listOf("net", "user", "b4-agent"),
                listOf("icacls", ws.toString(), "/grant", "b4-agent:(OI)(CI)M"),
                listOf("icacls", module.toString(), "/grant", "b4-agent:(OI)(CI)M"),
                listOf("icacls", m2.toString(), "/grant", "b4-agent:(OI)(CI)RX"),
                listOf("icacls", home.toString(), "/deny", "b4-agent:(F)"),
            ),
            runner.calls,
            "one grant per root, existing roots only; the workspace is never denied or granted twice",
        )
    }

    @Test
    @DisplayName("a failing read-only grant is a hard failure")
    fun failingReadOnlyGrantIsHardFailure() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val ws = workspace()
        val m2 = Files.createDirectories(tempDir.resolve("m2"))
        val runner = FakeRunner { command ->
            when {
                command.first() == "net" -> SandboxCommandResult(0, "User name b4-agent")
                command.size > 1 && command[1] == m2.toString() -> SandboxCommandResult(5, "Access is denied.")
                else -> SandboxCommandResult(0)
            }
        }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner).provision(ws, readOnlyRoots = listOf(m2))
        }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("read-only root"), error.message!!)
        assertTrue(error.message!!.contains(m2.toString()), error.message!!)
    }

    @Test
    @DisplayName("a failing deny is a hard failure")
    fun failingDenyIsHardFailure() {
        assumeTrue(isWindows, "icacls only exists on Windows")
        val ws = workspace()
        val home = Files.createDirectories(tempDir.resolve("home"))
        val runner = FakeRunner { command ->
            when {
                command.first() == "net" -> SandboxCommandResult(0, "User name b4-agent")
                command.size > 2 && command[2] == "/deny" -> SandboxCommandResult(5, "Access is denied.")
                else -> SandboxCommandResult(0)
            }
        }

        val error = assertThrows(SandboxUnavailableException::class.java) {
            bootstrap(runner).provision(ws, denyRoots = listOf(home))
        }

        assertTrue(error.message!!.contains("deny"), error.message!!)
        assertTrue(error.message!!.contains(home.toString()), error.message!!)
    }

    @Test
    @DisplayName("the credential store decodes the base64 UTF-16LE payload from Credential Manager")
    fun credentialStoreDecodesUtf16() {
        val encoded = Base64.getEncoder().encodeToString("p@ss".toByteArray(Charsets.UTF_16LE))
        val runner = FakeRunner { SandboxCommandResult(0, encoded) }
        val store = WindowsCredentialStore(
            target = "browser4:t",
            dpapiFile = tempDir.resolve("absent.cred"),
            runner = runner,
        )

        assertArrayEquals("p@ss".toCharArray(), store.read())
        assertEquals(1, runner.calls.size, "the DPAPI file is not consulted when Credential Manager answers")
        assertTrue(runner.calls.single().first() == "powershell.exe")
    }

    @Test
    @DisplayName("the credential store reports null when neither source has the credential")
    fun credentialStoreReturnsNullWhenMissing() {
        val runner = FakeRunner { SandboxCommandResult(3, "") }
        val store = WindowsCredentialStore(
            target = "browser4:t",
            dpapiFile = tempDir.resolve("absent.cred"),
            runner = runner,
        )

        assertNull(store.read())
        assertEquals(2, runner.calls.size, "Credential Manager, then the DPAPI file")
        assertTrue(runner.calls.all { it.first() == "powershell.exe" && it.contains("-NoProfile") })
    }
}
