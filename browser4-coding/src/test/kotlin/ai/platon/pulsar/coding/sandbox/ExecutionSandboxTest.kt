package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.PermissionAudit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan C1/C2/P1-2 — the execution seam, the environment whitelist and the Windows ACL sandbox.
 *
 * The token launch itself needs a real Windows account, so it is covered by
 * [WindowsTokenLaunchTest]; here the contract is pinned: a sandbox that cannot provision fails hard
 * instead of falling back, and long-lived credentials are not inherited by agent-spawned processes
 * (unless explicitly declared, which is audited).
 */
class ExecutionSandboxTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("off and windows-acl resolve; known-but-unimplemented and unknown ids fail hard")
    fun sandboxResolution() {
        assertEquals("off", ExecutionSandboxes.of("").id)
        assertEquals("off", ExecutionSandboxes.of("off").id)
        assertEquals("off", ExecutionSandboxes.of("  OFF  ").id)
        assertEquals("windows-acl", ExecutionSandboxes.of("windows-acl").id)
        assertEquals("windows-acl", ExecutionSandboxes.of("  WINDOWS-ACL  ").id)

        // Plan P3-1: the Linux/macOS ids are recognized, and selecting one is a hard failure in
        // this build rather than a silent fallback.
        for (id in listOf("bwrap", "container", "nsjail", "sandbox-exec")) {
            val error = assertThrows(SandboxUnavailableException::class.java) { ExecutionSandboxes.of(id) }
            assertEquals(id, error.sandboxId)
            assertTrue(error.message!!.contains("not implemented in this build"), error.message!!)
            assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
        }

        // An unknown value is a different failure: it names every known id so a typo is obvious.
        val unknown = assertThrows(SandboxUnavailableException::class.java) {
            ExecutionSandboxes.of("bwrap-typo")
        }
        assertEquals("bwrap-typo", unknown.sandboxId)
        assertTrue(unknown.message!!.contains("Unknown sandbox"), unknown.message!!)
        assertTrue(unknown.message!!.contains("known ids", ignoreCase = true), unknown.message!!)
    }

    @Test
    @DisplayName("current() resolves once per id, so the account cache survives across commands")
    fun currentResolvesOncePerId() {
        val previous = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
        try {
            System.setProperty(ExecutionSandboxes.PROP_SANDBOX, "windows-acl")
            val first = ExecutionSandboxes.current()
            val second = ExecutionSandboxes.current()
            assertEquals("windows-acl", first.id)
            assertSame(first, second, "one instance per id keeps the per-workspace provisioning cache")

            System.setProperty(ExecutionSandboxes.PROP_SANDBOX, "off")
            assertEquals("off", ExecutionSandboxes.current().id)
        } finally {
            if (previous == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
            else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, previous)
        }
    }

    @Test
    @DisplayName("validateConfigured fails on an invalid id, so a typo stops the start")
    fun invalidConfigurationFailsValidation() {
        val previous = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
        try {
            ExecutionSandboxes.validateConfigured() // default: off

            for (id in listOf("bwrap-typo", "sandbox-exec")) {
                System.setProperty(ExecutionSandboxes.PROP_SANDBOX, id)
                assertThrows(SandboxUnavailableException::class.java) {
                    ExecutionSandboxes.validateConfigured()
                }
            }
        } finally {
            if (previous == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
            else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, previous)
        }
    }

    @Test
    @DisplayName("windows-acl provisions once per workspace and stamps the account on the spec")
    fun windowsAclProvisionsOncePerWorkspace() {
        val calls = mutableListOf<Path>()
        val sandbox = WindowsAclSandbox(
            provision = { workspace ->
                calls.add(workspace)
                SandboxAccount("b4-agent", charArrayOf('s'))
            },
            selfCheck = { _, _ -> null },
        )
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val spec = ProcessSpec(listOf("cmd.exe", "/c", "echo hi"), ws, mapOf("PATH" to "C:\\Windows"))

        val first = sandbox.prepare(spec, ws, allowNetwork = true)
        val second = sandbox.prepare(spec, ws, allowNetwork = false)

        assertEquals("windows-acl", sandbox.id)
        assertEquals("b4-agent", first.sandboxAccount?.userName)
        assertSame(first.sandboxAccount, second.sandboxAccount, "the account is cached per workspace")
        assertEquals(1, calls.size, "one bootstrap per workspace, not per command")
        assertEquals(ws.toAbsolutePath().normalize(), calls.single())
        assertEquals(listOf("cmd.exe", "/c", "echo hi"), first.command)
    }

    @Test
    @DisplayName("the write probe runs once per workspace (plan P3-2)")
    fun writeProbeRunsOncePerWorkspace() {
        val checks = mutableListOf<Path>()
        val sandbox = WindowsAclSandbox(
            provision = { SandboxAccount("b4-agent", charArrayOf('s')) },
            selfCheck = { workspace, _ ->
                checks.add(workspace)
                null
            },
        )
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val spec = ProcessSpec(listOf("cmd.exe"), ws, emptyMap())

        sandbox.prepare(spec, ws, allowNetwork = true)
        sandbox.prepare(spec, ws, allowNetwork = true)

        assertEquals(1, checks.size, "the two-write probe runs once per workspace")
    }

    @Test
    @DisplayName("the per-workspace cache is bounded and the evicted account's password is cleared")
    fun workspaceCacheIsBounded() {
        val accounts = mutableListOf<SandboxAccount>()
        val sandbox = WindowsAclSandbox(
            provision = {
                SandboxAccount("b4-agent", charArrayOf('s')).also { account -> accounts.add(account) }
            },
            selfCheck = { _, _ -> null },
            cacheLimit = 2,
        )
        val workspaces = (1..3).map { Files.createDirectories(tempDir.resolve("ws-$it")) }

        for (workspace in workspaces) {
            sandbox.prepare(ProcessSpec(listOf("cmd.exe"), workspace, emptyMap()), workspace, true)
        }

        assertEquals(3, accounts.size, "one provision per workspace")
        assertTrue(accounts[0].password().all { it == '\u0000' }, "the evicted workspace's password is zeroed")
        assertFalse(accounts[2].password().all { it == '\u0000' }, "the live workspace keeps its password")
    }

    @Test
    @DisplayName("a failing write probe is a hard failure, never an unwrapped launch")
    fun writeProbeFailureIsHardFailure() {
        val sandbox = WindowsAclSandbox(
            provision = { SandboxAccount("b4-agent", charArrayOf('s')) },
            selfCheck = { _, _ ->
                "the sandbox account cannot write inside the workspace 'C:\\ws'"
            },
        )
        val ws = Files.createDirectories(tempDir.resolve("ws"))

        val error = assertThrows(SandboxUnavailableException::class.java) {
            sandbox.prepare(ProcessSpec(listOf("cmd.exe"), ws, emptyMap()), ws, allowNetwork = true)
        }
        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("write probe failed"), error.message!!)
        assertTrue(error.message!!.contains("cannot write inside"), error.message!!)
        assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
    }

    @Test
    @DisplayName("a failing windows-acl provision is a hard failure, not an unwrapped launch")
    fun windowsAclFailureDoesNotFallBack() {
        val sandbox = WindowsAclSandbox(
            provision = {
                throw SandboxUnavailableException("windows-acl", "sandbox account 'b4-agent' is not usable")
            },
            selfCheck = { _, _ -> null },
        )
        val ws = Files.createDirectories(tempDir.resolve("ws"))

        val error = assertThrows(SandboxUnavailableException::class.java) {
            sandbox.prepare(ProcessSpec(listOf("cmd.exe"), ws, emptyMap()), ws, allowNetwork = true)
        }
        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("not usable"), error.message!!)
    }

    @Test
    @DisplayName("inherited secrets are dropped, toolchain variables survive")
    fun whitelistDropsInheritedSecrets() {
        val inherited = mapOf(
            "PATH" to "/bin",
            "HOME" to "/home/u",
            "JAVA_HOME" to "/jdk",
            "USERPROFILE" to "C:\\Users\\u",
            "DEEPSEEK_API_KEY" to "sk-deepseek",
            "OPENROUTER_API_KEY" to "sk-openrouter",
            "GITHUB_TOKEN" to "ghp_x",
            "SOME_RANDOM_VAR" to "1",
        )

        val env = SandboxEnvironment.build(inherited)

        assertEquals(setOf("PATH", "HOME", "JAVA_HOME", "USERPROFILE"), env.keys)
        assertFalse(env.containsValue("sk-deepseek"))
        assertFalse(env.containsValue("ghp_x"))
    }

    @Test
    @DisplayName("an explicit passthrough wins and is audited when secret-shaped")
    fun explicitPassthroughWins() {
        val before = PermissionAudit.snapshot()

        val env = SandboxEnvironment.build(
            inherited = mapOf("PATH" to "/bin", "DEEPSEEK_API_KEY" to "sk-deepseek"),
            explicit = mapOf("DEEPSEEK_API_KEY" to "sk-explicit", "B4_TASK_ID" to "t-1"),
        )

        assertEquals("sk-explicit", env["DEEPSEEK_API_KEY"])
        assertEquals("t-1", env["B4_TASK_ID"])
        assertEquals("sk-explicit", env["DEEPSEEK_API_KEY"])
        assertFalse(env.containsValue("sk-deepseek"))

        val after = PermissionAudit.snapshot()
        val events = (after["coding.env:${PermissionAudit.CODE_ENV_PASSTHROUGH}"] ?: 0) -
            (before["coding.env:${PermissionAudit.CODE_ENV_PASSTHROUGH}"] ?: 0)
        assertEquals(1, events, "only the secret-shaped variable is audited")
    }

    @Test
    @DisplayName("a non-secret explicit passthrough is not audited")
    fun nonSecretPassthroughIsQuiet() {
        val before = PermissionAudit.snapshot()
        SandboxEnvironment.build(inherited = emptyMap(), explicit = mapOf("B4_TASK_ID" to "t-1"))
        val after = PermissionAudit.snapshot()

        val events = (after["coding.env:${PermissionAudit.CODE_ENV_PASSTHROUGH}"] ?: 0) -
            (before["coding.env:${PermissionAudit.CODE_ENV_PASSTHROUGH}"] ?: 0)
        assertEquals(0, events)
    }

    @Test
    @DisplayName("the shell launches with the whitelist and still sees declared variables")
    fun shellSeesDeclaredEnvironment() = runBlocking {
        Files.createDirectories(tempDir.resolve("ws"))
        val shell = CodingAgentShell(baseDir = tempDir.resolve("ws"))
        shell.setEnv("B4_SANDBOX_TEST", "visible-value")

        val command = if (System.getProperty("os.name").lowercase().contains("win")) {
            "echo %B4_SANDBOX_TEST%"
        } else {
            "echo \"\$B4_SANDBOX_TEST\""
        }
        val output = shell.execute(command)

        assertTrue(output.contains("visible-value"), output)
    }
}
