package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Result of one external command the Windows bootstrap runs (`net`, `icacls`, `powershell`).
 */
data class SandboxCommandResult(
    val exitCode: Int,
    val output: String = "",
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Seam for the bootstrap's external commands.
 *
 * Tests inject this to simulate a missing account or a failing `icacls` without provisioning a real
 * Windows account; production uses [SystemSandboxCommandRunner].
 */
interface SandboxCommandRunner {
    fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult
}

/** Runs the command with the C2 environment whitelist, so backend secrets never reach it. */
object SystemSandboxCommandRunner : SandboxCommandRunner {
    override fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult {
        return try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(SandboxEnvironment.build(System.getenv()))
            val process = builder.start()
            // Wait first (the child may write more than the pipe buffer before exiting), then drain:
            // a timed-out child is killed before we read whatever it left in the pipe.
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            SandboxCommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                output = output,
                timedOut = !finished,
            )
        } catch (e: Exception) {
            SandboxCommandResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Supplies the sandbox account password (P1-1). `null` means "not provisioned" and is a hard
 * failure for [WindowsSandboxBootstrap] — never an empty password.
 */
fun interface SandboxCredentialSource {
    fun read(): CharArray?
}

/**
 * Default credential sources for the Windows sandbox account: the Credential Manager target first,
 * then a DPAPI-protected file.
 *
 * Both are read through Windows PowerShell, so this module keeps its dependency-free contract;
 * P1-2 adds the native `LogonUserW` call, not a second credential stack. The password travels as
 * base64 of UTF-16LE bytes because PowerShell's console encoding differs between Windows PowerShell
 * 5 and PowerShell 7.
 *
 * Provisioning, done once by an administrator:
 * - Credential Manager: `cmdkey /add:browser4:agent-sandbox /user:<account> /pass:<password>`
 * - DPAPI file: write the UTF-16LE password bytes to `%LOCALAPPDATA%\browser4\sandbox\agent.cred`
 *   protected with `ProtectedData.Protect(..., CurrentUser)`.
 */
class WindowsCredentialStore(
    private val target: String = DEFAULT_TARGET,
    private val dpapiFile: Path = defaultDpapiFile(),
    private val runner: SandboxCommandRunner = SystemSandboxCommandRunner,
) : SandboxCredentialSource {

    companion object {
        /** Default Credential Manager target; override per account in [WindowsSandboxBootstrap]. */
        const val DEFAULT_TARGET = "browser4:agent-sandbox"

        private const val TIMEOUT_SECONDS = 30L

        private val logger = LoggerFactory.getLogger(WindowsCredentialStore::class.java)

        /** `%LOCALAPPDATA%\browser4\sandbox\agent.cred` — the DPAPI fallback location. */
        fun defaultDpapiFile(): Path {
            val local = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            return Path.of(local, "browser4", "sandbox", "agent.cred")
        }
    }

    override fun read(): CharArray? = readCredentialManager() ?: readDpapiFile()

    private fun readCredentialManager(): CharArray? {
        val escapedTarget = escapeSingleQuotes(target)
        val script = """
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            public static class B4Credential {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                private struct CREDENTIAL {
                    public int Flags;
                    public int Type;
                    public string TargetName;
                    public string Comment;
                    public long LastWritten;
                    public int CredentialBlobSize;
                    public IntPtr CredentialBlob;
                    public int Persist;
                    public int AttributeCount;
                    public IntPtr Attributes;
                    public string TargetAlias;
                    public string UserName;
                }
                [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
                private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);
                [DllImport("advapi32.dll", EntryPoint = "CredFree", SetLastError = true)]
                private static extern void CredFree(IntPtr credential);
                public static string Read(string target) {
                    IntPtr ptr = IntPtr.Zero;
                    if (!CredRead(target, 1, 0, out ptr) || ptr == IntPtr.Zero) { return null; }
                    try {
                        var c = (CREDENTIAL)Marshal.PtrToStructure(ptr, typeof(CREDENTIAL));
                        var bytes = new byte[c.CredentialBlobSize];
                        if (c.CredentialBlobSize > 0) { Marshal.Copy(c.CredentialBlob, bytes, 0, c.CredentialBlobSize); }
                        return Convert.ToBase64String(bytes);
                    } finally { CredFree(ptr); }
                }
            }
            '@
            [B4Credential]::Read('$escapedTarget')
        """.trimIndent()
        return decode(powerShell(script), "Credential Manager target '$target'")
    }

    private fun readDpapiFile(): CharArray? {
        val file = escapeSingleQuotes(dpapiFile.toAbsolutePath().normalize().toString())
        val script = """
            Add-Type -AssemblyName System.Security
            if (-not (Test-Path -LiteralPath '$file')) { exit 3 }
            [Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::Unprotect(
                [IO.File]::ReadAllBytes('$file'),
                [NullString]::Value,
                [System.Security.Cryptography.DataProtectionScope]::CurrentUser))
        """.trimIndent()
        return decode(powerShell(script), "DPAPI file '$dpapiFile'")
    }

    private fun powerShell(script: String): SandboxCommandResult =
        runner.run(
            listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script),
            TIMEOUT_SECONDS,
        )

    private fun decode(result: SandboxCommandResult, source: String): CharArray? {
        if (!result.ok) {
            logger.debug(
                "sandbox credential unavailable from {} (exit={}, timedOut={})",
                source, result.exitCode, result.timedOut,
            )
            return null
        }
        val encoded = result.output.trim()
        if (encoded.isEmpty()) return null
        return try {
            val text = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE).trimEnd('\u0000')
            if (text.isEmpty()) null else text.toCharArray()
        } catch (e: IllegalArgumentException) {
            logger.debug("sandbox credential from {} is not valid base64: {}", source, e.message)
            null
        }
    }
}

/**
 * The provisioned sandbox identity.
 *
 * The password stays out of [toString] because this object reaches logs and exception messages;
 * [password] returns a copy so a caller cannot mutate the stored secret.
 */
class SandboxAccount internal constructor(
    val userName: String,
    private val secret: CharArray,
) {
    /** Returns a copy, so a caller cannot mutate the stored secret. */
    fun password(): CharArray = secret.copyOf()

    /**
     * Zeroes the stored password.
     *
     * Called when the owning cache drops this workspace, so one password copy per abandoned
     * workspace does not stay in memory. The account must not be used after this: `password()`
     * returns zeros and a token launch would fail loudly instead of logging on.
     */
    fun dispose() {
        secret.fill('\u0000')
    }

    override fun toString(): String = "SandboxAccount(userName=$userName, password=<redacted>)"
}

/**
 * Plan P1-1 — the Windows sandbox account bootstrap.
 *
 * The Windows enforcement tier (P1-2/P1-3) runs every agent process as a dedicated low-privilege
 * local user and relies on NTFS ACLs for the actual boundary. This class is the provisioning half:
 * it proves the account exists, grants it write access to the workspace, and supplies its password,
 * which the token branch of [ProcessLauncher] (P1-2) hands to `LogonUserW`.
 *
 * It never creates the account and never falls back to an unwrapped launch: the account and its
 * credential are provisioned out of band by an administrator (the exception messages carry the
 * exact commands), so a missing account, a failing `icacls` or an unprovisioned credential is a
 * [SandboxUnavailableException] — the same "unavailable means fail" contract as [ExecutionSandboxes].
 *
 * "Everything else read-only" needs no explicit rule here: the account is a plain local user, so
 * its default rights are read/execute and it can write only to its own profile. Explicit deny
 * grants are P1-3.
 */
class WindowsSandboxBootstrap(
    private val accountName: String = defaultAccountName(),
    private val runner: SandboxCommandRunner = SystemSandboxCommandRunner,
    credentials: SandboxCredentialSource? = null,
) {
    companion object {
        /** The backend id carried by [SandboxUnavailableException.sandboxId]. */
        const val SANDBOX_ID = "windows-acl"

        /** Default low-privilege local account name; override with [PROP_ACCOUNT]. */
        const val DEFAULT_ACCOUNT = "browser4-agent"

        /** JVM property naming the sandbox account (default [DEFAULT_ACCOUNT]). */
        const val PROP_ACCOUNT = "browser4.agent.sandbox.account"

        private val logger = LoggerFactory.getLogger(WindowsSandboxBootstrap::class.java)

        private const val ACCOUNT_TIMEOUT_SECONDS = 20L
        private const val ICACLS_TIMEOUT_SECONDS = 30L
    }

    private val credentials: SandboxCredentialSource =
        credentials ?: WindowsCredentialStore(target = "browser4:$accountName", runner = runner)

    /**
     * Verifies and prepares [workspace] for the sandbox account.
     *
     * The credential is read before any ACL change so a misconfigured credential does not leave a
     * long-lived ACL change behind. The ACL matrix (P1-3) is then applied in order: writable roots
     * (`M`), read-only roots (`RX`), deny roots (`F`, folder object only).
     *
     * @param workspace the workspace root the sandbox account may write to
     * @param writableRoots additional roots the account may write to (workspace mirror targets)
     * @param readOnlyRoots roots the account may read and execute but not change (toolchain roots)
     * @param denyRoots roots the account must not access; the deny applies to the folder itself and
     *   does not inherit, so an explicit grant on a descendant still wins
     * @return the account name and its password, ready for a `LogonUserW` call
     * @throws SandboxUnavailableException when Windows, the workspace, the account, its credential
     *   or any ACL change is unavailable; the sandbox stays off rather than degrading
     */
    fun provision(
        workspace: Path,
        writableRoots: List<Path> = emptyList(),
        readOnlyRoots: List<Path> = emptyList(),
        denyRoots: List<Path> = emptyList(),
    ): SandboxAccount {
        requireWindows()
        val ws = workspace.toAbsolutePath().normalize()
        if (!Files.isDirectory(ws)) fail("workspace '$ws' does not exist or is not a directory")
        requireAccount()
        val password = requireCredential()
        grantWorkspaceWrite(ws)
        val writable = existingDirectories(writableRoots).filterNot { samePath(it, ws) }
        writable.forEach { grantModify(it) }
        val readable = existingDirectories(readOnlyRoots)
            .filterNot { samePath(it, ws) || writable.any { root -> samePath(root, it) } }
        readable.forEach { grantReadOnly(it) }
        val denied = existingDirectories(denyRoots)
            .filterNot { samePath(it, ws) || (writable + readable).any { root -> samePath(root, it) } }
        denied.forEach { denyAccess(it) }
        logger.info(
            "windows sandbox ready: account={} workspace={} writable={} readOnly={} denied={}",
            accountName, ws, writable.size, readable.size, denied.size,
        )
        return SandboxAccount(accountName, password)
    }

    private fun requireWindows() {
        val os = System.getProperty("os.name", "")
        if (!os.lowercase().contains("win")) {
            fail("the 'windows-acl' sandbox requires Windows, but os.name='$os'")
        }
    }

    private fun requireAccount() {
        val result = runner.run(listOf("net", "user", accountName), ACCOUNT_TIMEOUT_SECONDS)
        if (!result.ok) {
            fail(
                "sandbox account '$accountName' is not usable (net user exit=${result.exitCode}): " +
                    firstLine(result.output) +
                    ". Provision it first, as an administrator: " +
                    "'net user $accountName <password> /add', in no privileged group.",
            )
        }
    }

    private fun requireCredential(): CharArray {
        val secret = credentials.read()
        if (secret == null || secret.isEmpty()) {
            fail(
                "no credential for sandbox account '$accountName'; register it with " +
                    "'cmdkey /add:browser4:$accountName /user:$accountName /pass:<password>' " +
                    "or write a DPAPI-protected file at '${WindowsCredentialStore.defaultDpapiFile()}'",
            )
        }
        return secret
    }

    private fun grantWorkspaceWrite(workspace: Path) {
        // (OI)(CI)M: inherit onto files and subdirectories, "modify" (read/write/delete) rights.
        val grant = "$accountName:(OI)(CI)M"
        val result = runner.run(
            listOf("icacls", workspace.toString(), "/grant", grant),
            ICACLS_TIMEOUT_SECONDS,
        )
        if (!result.ok) {
            fail(
                "could not grant '$accountName' write access to workspace '$workspace' " +
                    "(icacls exit=${result.exitCode}): ${firstLine(result.output)}. " +
                    "Run the grant once as an administrator.",
            )
        }
    }

    /** Same `M` grant as the workspace, for writable roots the workspace is mirrored from. */
    private fun grantModify(root: Path) {
        val result = runner.run(
            listOf("icacls", root.toString(), "/grant", "$accountName:(OI)(CI)M"),
            ICACLS_TIMEOUT_SECONDS,
        )
        if (!result.ok) {
            fail(
                "could not grant '$accountName' write access to writable root '$root' " +
                    "(icacls exit=${result.exitCode}): ${firstLine(result.output)}",
            )
        }
    }

    /** `RX`: read and execute, never write — a build may read a toolchain, not modify it. */
    private fun grantReadOnly(root: Path) {
        val result = runner.run(
            listOf("icacls", root.toString(), "/grant", "$accountName:(OI)(CI)RX"),
            ICACLS_TIMEOUT_SECONDS,
        )
        if (!result.ok) {
            fail(
                "could not grant '$accountName' read access to read-only root '$root' " +
                    "(icacls exit=${result.exitCode}): ${firstLine(result.output)}",
            )
        }
    }

    /**
     * `F`, folder object only.
     *
     * No `(OI)(CI)`: a recursive deny would inherit into the workspace and the read-only roots, and
     * among *inherited* ACEs deny beats allow, so a workspace nested under the denied root would
     * silently become read-only. Descendants keep their own ACLs, and an explicit grant always
     * takes precedence over this deny.
     */
    private fun denyAccess(root: Path) {
        val result = runner.run(
            listOf("icacls", root.toString(), "/deny", "$accountName:(F)"),
            ICACLS_TIMEOUT_SECONDS,
        )
        if (!result.ok) {
            fail(
                "could not deny '$accountName' access to '$root' " +
                    "(icacls exit=${result.exitCode}): ${firstLine(result.output)}",
            )
        }
    }

    private fun fail(message: String): Nothing {
        logger.warn("windows sandbox unavailable: {}", message)
        throw SandboxUnavailableException(SANDBOX_ID, "Windows sandbox unavailable: $message")
    }
}

/** Reads [WindowsSandboxBootstrap.PROP_ACCOUNT]; blank falls back to the default account. */
private fun defaultAccountName(): String =
    System.getProperty(WindowsSandboxBootstrap.PROP_ACCOUNT)?.trim()?.takeIf { it.isNotEmpty() }
        ?: WindowsSandboxBootstrap.DEFAULT_ACCOUNT

/**
 * Roots the sandbox account must not access (plan P1-3).
 *
 * The operator's home profile is the one place a low-privilege account could otherwise reach if the
 * profile carried a permissive ACE; gitignored credentials and personal files live there. The deny
 * is folder-object-only (see `denyAccess`), so the workspace and the read-only toolchain roots — even
 * when nested under it — keep their explicit grants.
 */
internal fun defaultDenyRoots(): List<Path> =
    listOfNotNull(
        runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
            ?.toAbsolutePath()?.normalize(),
    )

/** Existing directories only: `icacls` on a missing root would fail the whole provisioning. */
private fun existingDirectories(roots: List<Path>): List<Path> =
    roots.map { it.toAbsolutePath().normalize() }
        .filter { Files.isDirectory(it) }
        .distinct()

private fun samePath(a: Path, b: Path): Boolean = a.toString().equals(b.toString(), ignoreCase = true)

/** First non-blank line of a command's output, bounded so it can go into an exception message. */
private fun firstLine(text: String, limit: Int = 200): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no output"
    return if (line.length <= limit) line else line.take(limit) + "..."
}

/** PowerShell single-quoted strings escape a quote by doubling it. */
private fun escapeSingleQuotes(value: String): String = value.replace("'", "''")
