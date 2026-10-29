package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory

/**
 * Enhanced shell command execution subsystem for AI coding agents.
 *
 * Extends [AgentShell]'s basic read-only command support to include full
 * development tooling: compilers, build systems, package managers, version control,
 * scripting runtimes, and more. Designed for use in autonomous coding agents that need
 * to write, build, test, and deploy code.
 *
 * ## Execution model
 *
 * Commands run through `cmd.exe /c` on Windows and `sh -c` elsewhere, so the
 * command syntax is the platform shell's, not a POSIX one. stdout/stderr are
 * decoded as strict UTF-8 first and as the OS native encoding second (see
 * [decodeProcessOutput]), which keeps localized Windows output readable.
 *
 * ## Security Model
 *
 * Commands are validated against a tiered allow-list:
 * - **safe** — read-only commands always permitted (subset of [AgentShell] whitelist)
 * - **dev** — development tools permitted when [allowDevTools] is true (default)
 * - **network** — network-accessing tools require explicit opt-in ([allowNetwork])
 * - **destructive** — write/delete operations require explicit opt-in ([allowDestructive])
 *
 * Environment variables can be injected per-session or per-command.
 */
class CodingAgentShell(
    private val baseDir: Path,
    private val defaultTimeoutSeconds: Long = 120L,
    private val allowDevTools: Boolean = true,
    private val allowNetwork: Boolean = false,
    private val allowDestructive: Boolean = true,
    /** v0.3: when false the shell's working directory is constrained to [baseDir]
     *  by lexical prefix matching (junction-aware, so module subdirectories
     *  work). When true any path is accepted — same switch as
     *  [CodingAgentFileSystem.allowExternalAccess] so the two tools stay in
     *  lockstep. */
    private val allowExternalAccess: Boolean = false,
    /**
     * Credential locations (home-relative, `~` expanded) that may never appear as a **path
     * argument**, whatever [allowExternalAccess] says. Same set as
     * [CodingAgentFileSystem.sensitivePaths] so the file tools and the shell agree.
     */
    private val sensitivePaths: Set<String> = CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS,
    /**
     * Workspace-relative directories the shell may not name at all (as an argument, a redirection
     * target or a working directory).
     *
     * The file tools already refuse to write under them ([CodingAgentFileSystem.protectedDirs]);
     * without this the shell was a one-line bypass — `echo forged >
     * .browser4/data/agent/agent-tasks.jsonl` passed validation because the argument guard only
     * knew about credential locations.
     */
    private val protectedDirs: Set<String> = CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS,
    /**
     * `-Dbrowser4.agent.shell.pathGuard=off` disables the credential argument guard.
     *
     * [protectedDirs] is unaffected: those are rails (the agent's own bookkeeping), not a
     * capability heuristic somebody may need to switch off locally.
     */
    private val pathGuardEnabled: Boolean =
        System.getProperty("browser4.agent.shell.pathGuard", "on").lowercase() != "off",

    /**
     * Whether a literal absolute path argument outside the workspace is refused when
     * [allowExternalAccess] is off (default `on`).
     *
     * Separate from [pathGuardEnabled] because the two answer different questions: the
     * credential guard is about secrets, this one is about the workspace boundary. Turn
     * it off with `-Dbrowser4.agent.shell.workspaceBoundary=off` for a local workflow that
     * genuinely needs to pass an absolute path outside the workspace.
     */
    private val workspaceBoundaryEnabled: Boolean =
        System.getProperty("browser4.agent.shell.workspaceBoundary", "on").lowercase() != "off",
    /**
     * `build-only` profile: development tools are narrowed to [BUILD_COMMANDS].
     *
     * Interpreters (`python`/`node`/`pwsh`/`sh`/`cmd`), cloud CLIs, containers, remote shells and
     * VCS are dev tools in the full profile but each is arbitrary code execution, which the
     * `write` and `read-build` presets do not grant. [AgentAccessPolicy.allowsExec] is not
     * consulted here on purpose: this shell backs the internal build runner.
     */
    private val buildOnly: Boolean = false,
    /**
     * When false the shell refuses every command up front.
     *
     * Used by [CodingComponents.shell] for the model-facing shell when the policy does not allow
     * execution; the internal build shell keeps it true (see [buildOnly]).
     */
    private val allowExecution: Boolean = true,
) {
    /**
     * The workspace this shell is bound to.
     *
     * Public because other tools that spawn processes must resolve relative paths the
     * same way the shell does: `b4.run` shipped a cwd of its own, and after a
     * workspace rebind it ran in the PREVIOUS task's workspace while `coding.*`
     * wrote into the new one (2026-10-28: a relative `--file .b4scratch/ev.js`
     * "not found" four times, pointing at another agent's directory). Reading the
     * root from the receiver makes the two agree by construction.
     */
    val workspaceRoot: Path get() = baseDir

    companion object {
        const val MAX_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT_CHARS = 200_000

        /** Tool name the audit records shell refusals under. */
        private const val SHELL_TOOL = "coding.shell"

        /** Prefix of every [argumentPathViolation] message; used to pick the audit code. */
        private const val PATH_GUARD_PREFIX = "path guard: "

        /** Prefix of every [protectedPathViolation] message; used to pick the audit code. */
        private const val PROTECTED_PATH_PREFIX = "protected path: "

        /** Redirection introduced by an argument (`>file`, `>>file`, `2>file`, `&>file`). */
        private val REDIRECTION_PREFIX = Regex("""^(?:\d+|&)?>>?""")

        private val logger = LoggerFactory.getLogger(CodingAgentShell::class.java)

        private val PS_ENV_VAR = Regex("""\${'$'}env:([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
        private val WIN_ENV_VAR = Regex("""%([A-Za-z_][A-Za-z0-9_]*)%""")
        private val POSIX_ENV_VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")

        /** Words that identify a credential artefact when a variable cannot be expanded. */
        private val SENSITIVE_PATH_KEYWORDS = listOf(
            ".ssh", "id_rsa", "id_ed25519", ".aws", ".gnupg", "gcloud", ".browser4",
        )

        private val IS_WINDOWS: Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /** `/s`, `/b`, `/c:…` — Windows command switches, not POSIX paths. */
        private val WINDOWS_SWITCH = Regex("""^/[A-Za-z?][A-Za-z0-9:._-]*$""")

        /**
         * Build launchers that may be spelled with an absolute path outside the
         * workspace (`C:\tools\mvnw.cmd`, `/usr/bin/python3`). Data files stay contained.
         */
        private val LAUNCHER_BASENAMES = setOf(
            "mvnw", "mvnw.cmd", "mvnw.bat", "mvn", "mvn.cmd", "mvn.bat",
            "gradlew", "gradlew.bat", "gradle",
            "java", "javac", "javaw", "node", "npm", "npx", "pnpm", "yarn",
            "python", "python3", "python.exe", "py", "pip", "pip3",
            "pwsh", "powershell", "cmd", "bash", "sh", "zsh",
            "git", "cargo", "rustc", "go", "dotnet", "kotlin", "kotlinc",
        )

        /** Executable script/binary extensions — a launcher, never a data file. */
        private val EXECUTABLE_EXTENSIONS = setOf("cmd", "bat", "exe", "ps1", "sh", "py", "com", "msc")

        /**
         * Charset of the bytes a child process writes to stdout/stderr.
         *
         * On Windows `cmd.exe` and its built-ins emit the console code page
         * (cp936/GBK on a Chinese system) while modern tools they spawn (Maven,
         * git, python) emit UTF-8. The JVM default charset is UTF-8 since JEP 400
         * (Java 18+), so decoding a localized `dir` listing with it produced
         * U+FFFD garbage the agent could not read — it then re-ran the command
         * with different syntax instead of using the output.
         *
         * `native.encoding` is the JVM's record of the OS native encoding; it is
         * UTF-8 on Linux/macOS, so this only changes Windows behaviour.
         */
        private val NATIVE_OUTPUT_CHARSET: Charset = runCatching {
            val name = System.getProperty("native.encoding")
                ?: System.getProperty("sun.jnu.encoding")
            if (name.isNullOrBlank()) StandardCharsets.UTF_8 else Charset.forName(name)
        }.getOrElse { StandardCharsets.UTF_8 }

        /**
         * Decode a child process's stdout/stderr bytes.
         *
         * Strict UTF-8 when the bytes decode cleanly (Maven, git, python), and
         * the platform native encoding otherwise (cmd.exe built-ins on a
         * localized Windows). A leading UTF-8 BOM is stripped.
         */
        fun decodeProcessOutput(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val utf8 = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
            val text = utf8 ?: String(bytes, NATIVE_OUTPUT_CHARSET)
            return if (text.startsWith('\uFEFF')) text.substring(1) else text
        }

        // ------------------------------------------------------------------
        // Command categories
        // ------------------------------------------------------------------

        /** Read-only / safe commands — always permitted (subset of AgentShell whitelist) */
        val SAFE_COMMANDS = setOf(
            "ls", "dir", "pwd", "cd", "tree",
            "cat", "type", "less", "more", "head", "tail",
            "grep", "findstr", "awk", "sed",
            "wc", "sort", "uniq", "cut", "tr",
            "echo", "printf", "date", "time",
            "uname", "hostname", "uptime", "whoami", "id",
            "free", "df", "du", "ps", "top", "pgrep",
            "env", "printenv", "which", "where", "whereis",
            "ip", "ss",
            "man", "help", "info",
        )

        /** Development tools — permitted when [allowDevTools] is true */
        val DEV_COMMANDS = setOf(
            // Version control
            "git", "svn", "hg",
            // Java / JVM
            "java", "javac", "mvn", "mvnw", "gradle", "gradlew", "kotlin", "kotlinc",
            // Rust
            "cargo", "rustc", "rustup", "rustfmt",
            // Node.js / Frontend
            "node", "npm", "npx", "yarn", "pnpm", "tsc",
            "webpack", "vite", "esbuild",
            // Python
            "python", "python3", "pip", "pip3", "poetry", "uv", "pytest", "black", "ruff",
            // C / C++
            "gcc", "g++", "clang", "clang++", "make", "cmake", "ninja",
            // Go
            "go", "gofmt",
            // .NET
            "dotnet",
            // Shell scripting (inline scripts are recursively validated, see
            // validateSegments → inlineScript; `cmd` is Windows-only)
            "bash", "sh", "zsh", "dash", "powershell", "pwsh", "cmd",
            // Package management
            "apt", "apt-get", "brew", "choco", "winget", "yum", "dnf", "pacman",
            // Database clients
            "sqlite3", "psql", "mysql",
            // Container / orchestration
            "docker", "kubectl", "helm",
            // Infrastructure
            "terraform", "tofu",
            // Cloud CLIs
            "aws", "gcloud", "az",
            // SSH / remote
            "ssh", "scp",
        )

        /**
         * Build toolchain — the only development tools a [buildOnly] shell may run.
         *
         * Kept deliberately narrow: `mvn`/`cargo`/`npm` etc. execute the project's own build
         * scripts, so `read-build` is about preventing accidental damage and data exfiltration,
         * not about sandboxing hostile code (see the plan's threat model).
         */
        val BUILD_COMMANDS = setOf(
            "mvn", "mvnw", "gradle", "gradlew",
            "cargo", "rustc",
            "npm", "npx", "tsc",
            "go", "dotnet",
            "make", "cmake", "ninja", "javac", "kotlinc",
        )

        /** Commands that access the network — always require [allowNetwork] */
        val NETWORK_COMMANDS = setOf(
            "curl", "wget", "nc", "telnet", "nslookup", "dig",
        )

        /** Commands that can modify filesystem outside workspace */
        val DESTRUCTIVE_COMMANDS = setOf(
            "rm", "del", "rmdir", "mv", "move", "cp", "copy", "xcopy",
            "chmod", "chown", "icacls",
            "mount",
            "dd", "mkfs", "fdisk",
            "kill", "killall", "pkill", "taskkill",
            "shutdown", "reboot",
            "systemctl", "service", "sc",
        )

        /**
         * Commands the coding shell NEVER runs, whatever the flags say.
         *
         * Link/mount tools rewrite the workspace *topology*: a link the agent
         * creates (or deletes) turns later path resolution into something the
         * operator never approved. Observed in a real run: an agent used
         * `mklink /J` to rebuild a dozen module junctions inside its workspace,
         * then deleted and re-created one mid-run. The workspace is now
         * provisioned by the framework itself (`WorkspaceMirror`), so the agent
         * has no legitimate need for these tools.
         */
        val FORBIDDEN_COMMANDS = setOf(
            "mklink", "ln", "mount",
        )

        /**
         * Commands that terminate processes.
         *
         * They are not forbidden — killing a stray dev server is legitimate — but a task
         * must not kill the runtime that executes it (see [processKillViolation]).
         */
        private val PROCESS_KILL_COMMANDS = setOf(
            "taskkill", "tskill", "pskill", "kill", "pkill", "killall", "stop-process", "spps",
        )

        /** The pid named by `taskkill /PID 42`, `Stop-Process -Id 42`, `kill -9 42`, … */
        private val PID_ARGUMENT = Regex(
            """(?i)(?:/pid[=\s]+|-id[=\s]+|--pid[=\s]+|\bkill\s+(?:-\w+\s+)*)(\d+)"""
        )

        private val BLOCKED_PATTERNS = listOf(
            Regex("rm\\s+-[^\\s]*r[^\\s]*\\s+/\\s*$"),
            Regex("rm\\s+-[^\\s]*r[^\\s]*\\s+/\\*"),
            Regex("mkfs\\."),
            Regex("dd\\s+.*of=/dev/"),
            Regex("shutdown"),
            Regex("reboot"),
            Regex("init\\s+[06]"),
            Regex(":\\(\\)\\{"),
            Regex(">\\s*/dev/sd"),
            Regex("format\\s+[a-z]:", RegexOption.IGNORE_CASE),
        )

        /** `VAR=value` prefix that may precede the executable in a simple command. */
        private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

        /**
         * Executable suffixes stripped by [normalizeCommandName] before allow-list
         * membership. `.cmd`/`.bat`/`.exe`/`.com` cover Windows launchers
         * (`mvnw.cmd` → `mvnw`, which is what the DEV allow-list names).
         */
        private val EXECUTABLE_SUFFIXES = listOf(".exe", ".cmd", ".bat", ".com", ".ps1")

        /** Recursion guard for `sh -c "sh -c '...'"` style nesting. */
        private const val MAX_INLINE_SCRIPT_DEPTH = 3

        /**
         * Head budget kept for compilers/tests/linters when the output is
         * truncated: enough for the reactor banner, while the tail keeps the
         * "BUILD FAILURE" verdict and the last diagnostics.
         */
        private const val DIAGNOSTIC_HEAD_CHARS = 2_000

        /**
         * Commands whose output must be truncated tail-first (the verdict and
         * the actionable errors are at the end).
         */
        private val DIAGNOSTIC_COMMAND = Regex(
            """(^|[\s/\\])(mvn|mvnw|mvnw\.cmd|mvn\.cmd|gradle|gradlew|cargo|npm|pnpm|yarn|pytest|go|dotnet|tsc|eslint|ruff|black|ktlint|javac)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    private val sessionCounter = AtomicLong(0)
    private val results = ConcurrentHashMap<String, ShellResult>()

    /** Per-session environment variables injected into all commands */
    private val sessionEnv: MutableMap<String, String> = ConcurrentHashMap()

    /** Base working directory — resolved to canonical path for security checks */
    private val canonicalBaseDir: Path = baseDir.toRealPath()
    /** Canonical workspace directory used as the default command working directory. */
    val canonicalWorkspaceDir: Path get() = canonicalBaseDir

    // ------------------------------------------------------------------
    // Per-instance allow / deny overrides
    // ------------------------------------------------------------------

    /** Explicitly allowed base command names (e.g. "ffmpeg"). Overrides category membership. */
    private val explicitAllow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Explicitly denied base command names (e.g. "git"). Takes priority over everything except blocked patterns. */
    private val explicitDeny: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Regex patterns that allow matching commands. The full command string is tested. */
    private val allowPatterns: MutableList<Regex> = mutableListOf()

    /** Regex patterns that deny matching commands. Takes priority over allow patterns. */
    private val denyPatterns: MutableList<Regex> = mutableListOf()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Whether [baseCommand] is permitted by the enabled development-tool profile.
     *
     * In [buildOnly] mode the profile is [BUILD_COMMANDS] instead of [DEV_COMMANDS]; everything
     * else (safe, network, destructive, explicit allow/deny) is unchanged.
     */
    private fun devToolAllowed(baseCommand: String): Boolean =
        allowDevTools && baseCommand in if (buildOnly) BUILD_COMMANDS else DEV_COMMANDS

    /**
     * Execute a command with full development tool support.
     *
     * @param command The command to execute
     * @param timeoutSeconds Timeout in seconds (default: 120, max: 600)
     * @param workingDir Working directory override (absolute, or relative to baseDir)
     * @param env Additional environment variables for this command
     * @return Formatted execution result string
     */
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
    ): String {
        if (!allowExecution) {
            return "Error: " + execDisabledRefusal()
        }
        if (command.isBlank()) {
            return "Error: Command must not be blank."
        }

        val violation = validateCommand(normalizeShellCommand(command))
        if (violation != null) {
            return "Error: " + commandRefusal(violation)
        }

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"

        // v0.3: resolveWorkingDir now throws on bad paths — we do NOT catch
        // WorkspaceEscapeException/WorkspaceDirMissingException here; they
        // must surface to the ToolExecutionCoordinator's [fail] wrapper.
        val dir = resolveWorkingDir(workingDir)

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, normalizeShellCommand(command), effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            formatResult(result)
        } catch (e: IOException) {
            "Error: I/O failure for '$command' — ${e.message ?: ""}"
        } catch (e: SecurityException) {
            "Error: Permission denied for '$command' — ${e.message ?: ""}"
        } catch (e: WorkspaceEscapeException) {
            throw e  // v0.3: propagate to caller so [fail] can be emitted
        } catch (e: WorkspaceDirMissingException) {
            throw e  // v0.3: propagate
        } catch (e: Exception) {
            logger.warn("Unexpected error executing '$command'", e)
            "Error: Unexpected error executing '$command' — ${e.message ?: ""}"
        }
    }

    /**
     * Execute a command and return the raw [ShellResult].
     */
    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        workingDir: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ShellResult {
        if (!allowExecution) {
            return ShellResult("", command, -1, "", execDisabledRefusal(), 0)
        }
        if (command.isBlank()) {
            return ShellResult("", command, -1, "", "Command must not be blank.", 0)
        }

        val violation = validateCommand(normalizeShellCommand(command))
        if (violation != null) {
            return ShellResult("", command, -1, "", commandRefusal(violation), 0)
        }

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"
        // v0.3: was `resolveWorkingDir(workingDir) ?: canonicalBaseDir.toFile()` —
        // the silent fallback hid misconfiguration from the caller. Now we throw.
        val dir = resolveWorkingDir(workingDir)

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, normalizeShellCommand(command), effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            result
        } catch (e: WorkspaceEscapeException) {
            throw e  // v0.3: propagate
        } catch (e: WorkspaceDirMissingException) {
            throw e  // v0.3: propagate
        } catch (e: Exception) {
            ShellResult(sessionId, command, -1, "", e.message ?: "Unknown error", 0)
        }
    }

    fun readOutput(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No result found for session '$sessionId'."
        return formatResult(result)
    }

    fun getStatus(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No session found with ID '$sessionId'."
        val status = if (result.success) "SUCCESS" else "FAILED"
        return "Session '$sessionId': status=$status, exitCode=${result.exitCode}, " +
            "timedOut=${result.timedOut}, duration=${result.durationMs}ms, " +
            "command='${result.command}'"
    }

    fun listSessions(): String {
        if (results.isEmpty()) return "No shell sessions recorded."
        val sb = StringBuilder()
        sb.appendLine("Shell sessions (${results.size} total):")
        for ((id, result) in results) {
            val status = if (result.success) "SUCCESS" else "FAILED"
            sb.appendLine("- $id: status=$status, command='${result.command}', duration=${result.durationMs}ms")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Set a persistent environment variable for all subsequent commands in this session.
     */
    fun setEnv(name: String, value: String) {
        sessionEnv[name] = value
    }

    /**
     * Remove a persistent environment variable.
     */
    fun unsetEnv(name: String) {
        sessionEnv.remove(name)
    }

    /**
     * Get all session environment variables.
     */
    fun getEnv(): Map<String, String> = sessionEnv.toMap()

    // ------------------------------------------------------------------
    // Allow / deny API
    // ------------------------------------------------------------------

    /**
     * Allow a specific command by its base name (e.g. "ffmpeg", "pandoc").
     * This adds the command to a per-instance allow-list that overrides
     * the category check — even if the command is not in any predefined set,
     * it will be permitted.
     *
     * Example:
     * ```kotlin
     * shell.allowCommand("ffmpeg")    // allow ffmpeg even though it's not in DEV_COMMANDS
     * shell.allowCommand("pandoc")    // allow pandoc
     * ```
     */
    fun allowCommand(name: String) {
        explicitAllow.add(name.lowercase())
        explicitDeny.remove(name.lowercase())
    }

    /**
     * Deny a specific command by its base name (e.g. "git", "curl").
     * This adds the command to a per-instance deny-list. The deny list
     * takes priority over everything — even SAFE_COMMANDS and explicit
     * allows — so you can lock down a command entirely.
     *
     * Example:
     * ```kotlin
     * shell.denyCommand("git")        // block git entirely
     * shell.denyCommand("curl")       // block curl even if allowNetwork=true
     * ```
     */
    fun denyCommand(name: String) {
        explicitDeny.add(name.lowercase())
        explicitAllow.remove(name.lowercase())
    }

    /**
     * Remove a command from both the allow and deny lists, reverting
     * to the default category-based policy.
     */
    fun resetCommand(name: String) {
        explicitAllow.remove(name.lowercase())
        explicitDeny.remove(name.lowercase())
    }

    /**
     * Allow commands matching a regex pattern. The full command string
     * (e.g. "pip install requests") is tested against the pattern.
     *
     * Example:
     * ```kotlin
     * shell.allowPattern(Regex("pip\\s+install.*"))   // allow pip install
     * shell.allowPattern(Regex("npm\\s+run\\s+.*"))   // allow npm run <script>
     * ```
     */
    fun allowPattern(pattern: Regex) {
        allowPatterns.add(pattern)
    }

    /**
     * Deny commands matching a regex pattern. Deny patterns take priority
     * over allow patterns — if a command matches both, it is denied.
     *
     * Example:
     * ```kotlin
     * shell.denyPattern(Regex("git\\s+push.*--force"))  // block force push
     * shell.denyPattern(Regex("rm\\s+-rf\\s+/"))         // block recursive root delete
     * ```
     */
    fun denyPattern(pattern: Regex) {
        denyPatterns.add(pattern)
    }

    /**
     * Explain the current effective policy for a command — why it is allowed or
     * denied.
     *
     * Delegates to [validateCommand] so the explanation can never disagree with
     * the decision: the two drifted apart once (the explanation still reported
     * the head token of a chained command as allowed while the validator
     * rejected the chain).
     */
    fun explain(command: String): String {
        val violation = validateCommand(command)
        if (violation != null) return "'$command' is DENIED — $violation"

        val base = extractBaseCommand(command)
        val reason = when {
            base in explicitAllow -> "'$base' is in the explicit allow list"
            base in SAFE_COMMANDS -> "'$base' is a safe command"
            devToolAllowed(base) ->
                if (buildOnly) "'$base' is a build tool (build-only profile)"
                else "'$base' is a dev tool (allowDevTools=true)"
            allowNetwork && base in NETWORK_COMMANDS -> "'$base' is a network command (allowNetwork=true)"
            allowDestructive && base in DESTRUCTIVE_COMMANDS -> "'$base' is a destructive command (allowDestructive=true)"
            else -> "every command in the chain is allowed (pattern / path / PATH rule)"
        }
        return "'$command' is ALLOWED — $reason"
    }

    /**
     * Check whether a command would be allowed without actually executing it.
     */
    fun isAllowed(command: String): Boolean {
        return validateCommand(command) == null
    }

    // ------------------------------------------------------------------
    // Working directory resolution (v0.3 rewrite for lexical containment)
    // ------------------------------------------------------------------

    /** Resolve the effective working directory for one shell invocation.
     *
     * v0.3 replaces the previous `File?` return with typed exceptions so
     * callers (S1) can translate them to `[fail]` without matching on strings.
     * The checks mirror [CodingAgentFileSystem.resolvePath] so the two tools
     * stay on the same containment policy.
     *
     * Rules:
     * - No argument → canonicalBaseDir (always valid, auto-creates).
     * - Absolute path → must already exist AND must be lexically inside
     *   canonicalBaseDir (or allowExternalAccess=true).
     * - Relative path → resolved against canonicalBaseDir; the resulting
     *   path must be lexically inside canonicalBaseDir; non-parent directories
     *   are auto-created (agent is expected to want a new subdir); absolute
     *   existence is still required for external callers' invariants.
     *
     * We do NOT use `toRealPath()`: workspace module roots are junctions whose
     * real path lands in the source checkout, so a lexical check against
     * canonicalBaseDir is the only stable one.
     */
    private fun resolveWorkingDir(workingDir: String?): File {
        val raw = workingDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val candidate: Path = when {
            raw == null -> canonicalBaseDir
            raw.isAbsolute -> raw.normalize()
            else -> canonicalBaseDir.resolve(raw).normalize()
        }
        if (!allowExternalAccess && !candidate.startsWith(canonicalBaseDir)) {
            throw WorkspaceEscapeException(candidate, canonicalBaseDir)
        }
        // A working directory inside a protected subtree would make every relative path in the
        // command a protected write (`cd .browser4 && echo forged > agent-tasks.jsonl`).
        protectedRoots.firstOrNull { candidate.startsWith(it) }?.let { root ->
            throw WorkspaceProtectedPathException(
                candidate, canonicalBaseDir.relativize(root).toString().replace('\\', '/')
            )
        }
        if (!java.nio.file.Files.exists(candidate)) {
            // Only auto-create a relative subdirectory — absolute external
            // paths (and anything rejected above) must already exist.
            if (raw == null || raw.isAbsolute) {
                throw WorkspaceDirMissingException(candidate)
            }
            runCatching { java.nio.file.Files.createDirectories(candidate) }
                .onFailure { throw WorkspaceDirMissingException(candidate, it.message) }
        }
        if (!java.nio.file.Files.isDirectory(candidate)) {
            throw WorkspaceDirMissingException(candidate, "path is not a directory")
        }
        return candidate.toFile()
    }

    // ------------------------------------------------------------------
    // Command validation
    // ------------------------------------------------------------------

    /**
     * Structured refusal for a command the tiered allow-list refused.
     *
     * The reason itself is kept verbatim, because tools outside this module match on it (for example
     * `bin/eval-devtask.ps1` recognises `Command blocked` and `is not allowed (safe=`), but it is now
     * wrapped in the `PERMISSION_DENIED | code=… | tool=… | detail=… | hint=…` envelope so the model,
     * the CLI and `FailureCategory` can all tell a policy refusal from a command that simply failed.
     */
    private fun commandRefusal(violation: String): String {
        // `contains`, not `startsWith`: a violation can be wrapped by a nested-command layer
        // (`command substitution rejected — path guard: …`) and the specific audit code is still
        // the one the inner guard produced.
        val (code, hint) = when {
            violation.contains(PATH_GUARD_PREFIX) ->
                PermissionAudit.CODE_COMMAND_PATH_DENIED to
                    "pass a path inside the workspace; credential locations are never valid arguments"
            violation.contains(PROTECTED_PATH_PREFIX) ->
                PermissionAudit.CODE_PROTECTED_PATH to
                    "use the coding.* tools for the agent's own bookkeeping (.browser4) and CI " +
                        "definitions (.github/workflows); the shell may not touch them"
            else ->
                PermissionAudit.CODE_COMMAND_NOT_ALLOWED to
                    "run the build through coding.mvnBuild/coding.verify; ask the operator for access=full " +
                        "if this command is genuinely needed"
        }
        return PermissionAudit.denied(
            tool = SHELL_TOOL,
            code = code,
            detail = "Command blocked — $violation",
            hint = hint,
        )
    }

    /** Structured refusal for the `allowExecution=false` presets. */
    private fun execDisabledRefusal(): String = PermissionAudit.denied(
        tool = SHELL_TOOL,
        code = PermissionAudit.CODE_EXEC_DISABLED,
        detail = "shell execution is disabled by the access policy",
        hint = "re-run with access=full to run shell commands",
    )

    /** True when the host is Windows, where commands run through `cmd.exe /c`. */
    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * Platform-specific command normalization, applied before validation AND execution.
     *
     * `cmd.exe` spells some idioms differently from the command name the allow-list
     * checks: `echo.` (print a blank line) is not `echo`, so it was rejected as an
     * unknown command and the model had no clean way to emit a separator (2026-10-28:
     * agents worked around it with `python -c` and lost time). The same normalization
     * runs on the executed text, so the validated command and the executed command
     * cannot diverge.
     */
    internal fun normalizeShellCommand(command: String): String {
        if (!isWindowsHost()) return command
        return CMD_ECHO_DOT.replace(command) { match ->
            match.groupValues[1] + "echo"
        }
    }

    /** `echo.` / `echo. text` at a command position → `echo` (cmd's blank-line idiom). */
    private val CMD_ECHO_DOT = Regex("""(^|[&|;]\s*)echo\.(?=\s|$)""", RegexOption.IGNORE_CASE)

    /** cmd.exe flow-control keywords: valid in a script, never as a standalone command. */
    private val CMD_CONTROL_KEYWORDS = setOf("if", "for")

    /**
     * Validate a whole command line, not just its first token.
     *
     * A shell command is a *pipeline* of simple commands: `&&`, `||`, `;`, `|`,
     * `&`, newlines, command substitution (`$(...)`, backticks) and inline
     * scripts (`sh -c '...'`, `pwsh -Command '...'`, `cmd /c ...`) each carry
     * more commands. Validating only the head token let `ls && rm -rf x` and
     * `sh -c "curl http://evil"` through — the historical sandbox hole (plan
     * P0.7). Every base command found is validated against the same tiered
     * allow-list; nested inline scripts are validated recursively.
     *
     * @return a human-readable violation reason, or null when allowed
     */
    private fun validateCommand(command: String): String? {
        // Normalize first so a policy query (`isAllowed` / `explain`) agrees with what
        // would actually be executed: `echo.` is cmd's blank-line idiom for `echo`.
        val normalizedCommand = normalizeShellCommand(command)
        if (normalizedCommand.isBlank()) return "empty or invalid command"

        // 1. Hardcoded blocked patterns — whole string, cannot be overridden
        blockedPatternViolation(normalizedCommand)?.let { return it }

        // 2. Explicit deny / deny patterns — whole-string vetoes (they are
        //    authored as full-command regexes), re-checked per segment below
        extractBaseCommand(normalizedCommand).lowercase().let { base ->
            if (base.isNotEmpty() && base in explicitDeny) return "'$base' is explicitly denied"
        }
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(normalizedCommand)) return "matches deny pattern: ${pattern.pattern}"
        }

        // 3. Explicit allow / allow patterns — whole-string opt-ins bypass the
        //    per-token checks (the operator authored a trust rule for this shape)
        if (extractBaseCommand(normalizedCommand).lowercase() in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(normalizedCommand)) return null
        }

        // 4. Every simple command in the pipeline / substitution / inline script
        return validateSegments(normalizedCommand, depth = 0)
    }

    private fun blockedPatternViolation(command: String): String? {
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return "matches blocked pattern: ${pattern.pattern}"
            }
        }
        return null
    }

    /** Recursively validate every simple command inside [command]. */
    private fun validateSegments(command: String, depth: Int): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return "nested inline shell script exceeds $MAX_INLINE_SCRIPT_DEPTH levels — refusing to guess"
        }
        blockedPatternViolation(command)?.let { return it }

        val segments = splitSimpleCommands(command)
        if (segments.isEmpty()) return "empty or invalid command"
        for (segment in segments) {
            validateSimpleCommand(segment, depth)?.let { return it }
        }
        return null
    }

    /** Home-expanded credential roots, shared with [CodingAgentFileSystem]. */
    private val sensitiveRoots: List<Path> by lazy { SensitivePaths.roots(sensitivePaths) }

    /** Absolute roots of [protectedDirs] under the workspace base. */
    private val protectedRoots: List<Path> by lazy {
        protectedDirs.map { canonicalBaseDir.resolve(it).normalize() }
    }

    /**
     * Rejects path arguments that resolve into a credential location.
     *
     * The shell already contains the *working directory* to [canonicalBaseDir], but arguments were
     * never inspected: `type %USERPROFILE%\.ssh\id_rsa` (Windows), `cat ~/.ssh/id_rsa` or
     * `ssh -i $env:USERPROFILE\.ssh\id_rsa` all passed. Paths inside the workspace and ordinary
     * absolute paths outside it stay allowed — this guard is only about credentials, so builds
     * that reference `~/.m2` or an external SDK are untouched.
     *
     * An undefined variable makes the token unresolvable; such a token is allowed unless it also
     * names a credential artefact, so `%JAVA_HOME%\bin\mvn` keeps working while
     * `%SOMETHING%\.ssh\id_rsa` is still refused.
     */
    private fun argumentPathViolation(tokens: List<String>): String? {
        if (!pathGuardEnabled) return null
        for (token in tokens) {
            val candidate = pathCandidate(token) ?: continue
            val expanded = expandVariables(candidate)
            if (expanded == null) {
                if (SENSITIVE_PATH_KEYWORDS.any { candidate.lowercase().contains(it) }) {
                    return PATH_GUARD_PREFIX +
                        "'$token' names a credential location through an undefined variable"
                }
                continue
            }
            val path = runCatching { Path.of(expanded) }.getOrNull() ?: continue
            val resolved = if (path.isAbsolute) path.normalize()
            else canonicalBaseDir.resolve(expanded).normalize()
            if (resolved.startsWith(canonicalBaseDir)) {
                // The lexical path is inside the workspace, but a link inside it may still resolve
                // into a credential location (module junctions are links too, so only the sensitive
                // roots are checked here, not "outside the workspace").
                if (!isWorkspaceOwned(resolved) && SensitivePaths.isSensitive(resolved, sensitiveRoots)) {
                    return PATH_GUARD_PREFIX + "'$token' links to a credential location ($resolved); " +
                        "set -Dbrowser4.agent.shell.pathGuard=off to bypass"
                }
                continue
            }
            if (SensitivePaths.isSensitive(resolved, sensitiveRoots)) {
                return PATH_GUARD_PREFIX + "'$token' resolves to a credential location ($resolved); " +
                    "set -Dbrowser4.agent.shell.pathGuard=off to bypass"
            }
        }
        return null
    }

    /**
     * Rejects a literal absolute path argument that leaves the workspace while
     * [allowExternalAccess] is off.
     *
     * `coding.*` file tools cannot read outside the workspace, but the shell could: a
     * live run used `dir /s /b "D:\workspace\…"` and `git -C "D:\repo" show …` to hunt
     * for a fixture the file tools refused to read (2026-10-28). The boundary therefore
     * applies to arguments too, with deliberate exceptions so ordinary builds keep
     * working:
     * - standard toolchain locations (JDK, `~/.m2`, `~/.gradle`, `~/.cache`, temp,
     *   `%SystemRoot%`) stay readable;
     * - a token whose variables cannot be expanded is left alone (it is not a path we
     *   can judge);
     * - Windows `/s`, `/b` … switches are not paths.
     *
     * Inline scripts (`python -c "…"`) are not parsed — this guard is about the shell's
     * own arguments, and a script is free to compute whatever it likes.
     */
    private fun workspaceBoundaryViolation(tokens: List<String>): String? {
        if (!workspaceBoundaryEnabled || allowExternalAccess) return null
        for (token in tokens) {
            if (isWindowsSwitch(token)) continue
            val candidate = pathCandidate(token) ?: continue
            val expanded = expandVariables(candidate) ?: continue
            val path = runCatching { Path.of(expanded) }.getOrNull() ?: continue
            val relativeEscape = !path.isAbsolute && expanded.contains("..")
            if (!path.isAbsolute && !relativeEscape) continue
            val resolved = if (path.isAbsolute) path.normalize() else canonicalBaseDir.resolve(expanded).normalize()
            if (resolved.startsWith(canonicalBaseDir)) continue
            if (toolchainRoots.any { resolved.startsWith(it) }) continue
            // Credential locations stay the credential guard's business (so its switch
            // still governs them); this guard is about ordinary paths.
            if (SensitivePaths.isSensitive(resolved, sensitiveRoots)) continue
            // Build launchers are tools, not data: an absolute `mvnw`/`python3` outside
            // the workspace stays usable (the shell has always accepted those).
            if (isLauncherReference(resolved)) continue
            return PATH_GUARD_PREFIX + "'$token' resolves outside the workspace ($resolved); " +
                "use a workspace-relative path, copy the input into the workspace, or set " +
                "-Dbrowser4.agent.allowExternalAccess=true"
        }
        return null
    }

    /** `/s`, `/b`, `/i` are switches on Windows, not POSIX paths. */
    private fun isWindowsSwitch(token: String): Boolean =
        IS_WINDOWS && WINDOWS_SWITCH.matches(token)

    /** A build launcher (or any executable script/binary) may live outside the workspace. */
    private fun isLauncherReference(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase() ?: return false
        if (name in LAUNCHER_BASENAMES) return true
        val extension = name.substringAfterLast('.', "")
        return extension.isNotEmpty() && extension in EXECUTABLE_EXTENSIONS
    }

    /** JDK, package caches, temp and the OS directory — what a build legitimately reaches. */
    private val toolchainRoots: List<Path> by lazy {
        val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
        listOfNotNull(
            runCatching { Path.of(System.getProperty("java.home")) }.getOrNull(),
            home?.resolve(".m2"),
            home?.resolve(".gradle"),
            home?.resolve(".cache"),
            runCatching { Path.of(System.getProperty("java.io.tmpdir")) }.getOrNull(),
            runCatching { Path.of(System.getenv("SystemRoot") ?: "C:/Windows") }.getOrNull(),
        ).map { it.toAbsolutePath().normalize() }
    }

    /**
     * Rejects any argument that names a workspace-protected directory (`.browser4`,
     * `.github/workflows`).
     *
     * The file tools already refuse to write there; the shell did not, so
     * `echo forged > .browser4/data/agent/agent-tasks.jsonl` rewrote the agent's own task ledger
     * and `cp pom.xml .github/workflows/ci.yml` replaced a CI definition without ever touching a
     * `coding.*` file tool. Redirection targets are inspected like any other argument, and both
     * reads and writes are refused: the shell has no business in the bookkeeping tree, and the
     * read tools are still available for it.
     */
    private fun protectedPathViolation(tokens: List<String>): String? {
        if (protectedRoots.isEmpty()) return null
        for (token in tokens) {
            val candidate = pathLikeValue(token) ?: continue
            val hit = protectedRootOf(candidate) ?: continue
            return PROTECTED_PATH_PREFIX +
                "'$token' is inside protected directory '${canonicalBaseDir.relativize(hit).toString().replace('\\', '/')}' — " +
                "the shell may not read or write it; use the coding.* file tools instead"
        }
        return null
    }

    /**
     * The protected root [candidate] lands in, or null.
     *
     * Checks the lexical form first so glob forms (`rm -rf .browser4/` plus a wildcard) and other
     * values that [Path.of] rejects are still caught, then the resolved absolute path for absolute
     * forms.
     */
    private fun protectedRootOf(candidate: String): Path? {
        val lexical = candidate.replace('\\', '/').removePrefix("./").trimStart('/')
        protectedDirs.firstOrNull { dir ->
            val d = dir.trim('/')
            lexical == d || lexical.startsWith("$d/") || lexical.startsWith("$d*") || lexical.startsWith("$d?")
        }?.let { dir -> return canonicalBaseDir.resolve(dir).normalize() }

        val path = runCatching { Path.of(candidate) }.getOrNull() ?: return null
        val resolved = if (path.isAbsolute) path.normalize() else canonicalBaseDir.resolve(candidate).normalize()
        return protectedRoots.firstOrNull { resolved.startsWith(it) }
    }

    /** Whether [candidate] resolves to a location inside the workspace (a real path check). */
    private fun isWorkspaceOwned(candidate: Path): Boolean = runCatching {
        if (Files.exists(candidate)) candidate.toRealPath().startsWith(canonicalBaseDir) else true
    }.getOrDefault(true)

    /**
     * The path-like part of [token], or null when the token cannot be a path
     * (bare flags, plain words, option names without a value).
     */
    private fun pathCandidate(token: String): String? {
        val value = pathLikeValue(token) ?: return null
        val looksLikePath = value.startsWith("~") || value.startsWith("%") || value.startsWith("$") ||
            value.contains('/') || value.contains('\\')
        return if (looksLikePath) value else null
    }

    /**
     * The value part of [token]: strips a shell redirection prefix (`>file`, `2>>file`), the
     * `--key=value` wrapper and surrounding quotes.
     */
    private fun pathLikeValue(token: String): String? {
        if (token.isEmpty()) return null
        var t = token
        REDIRECTION_PREFIX.find(t)?.let { m -> t = t.substring(m.range.last + 1) }
        if (t.startsWith("-")) {
            // `-Dkey=value`, `--file=value`: only the value can be a path.
            val eq = t.indexOf('=')
            if (eq < 0) return null
            t = t.substring(eq + 1)
        }
        t = t.trim('"', '\'').trimStart('>', '<', '&').trim()
        return t.ifEmpty { null }
    }

    /**
     * Expands `~`, `$VAR`, `${VAR}`, `$env:VAR` and `%VAR%`; returns null when any variable is
     * undefined (the result would be a guess).
     */
    private fun expandVariables(raw: String): String? {
        var unresolved = false
        var s = raw
        s = PS_ENV_VAR.replace(s) { m ->
            val v = System.getenv(m.groupValues[1])
            if (v == null) { unresolved = true; m.value } else v
        }
        s = WIN_ENV_VAR.replace(s) { m ->
            val v = System.getenv(m.groupValues[1])
            if (v == null) { unresolved = true; m.value } else v
        }
        s = POSIX_ENV_VAR.replace(s) { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val v = System.getenv(name)
            if (v == null) { unresolved = true; m.value } else v
        }
        if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1)
        return if (unresolved) null else s
    }

    /**
     * Validates the command substitutions that survive segmentation.
     *
     * [splitSimpleCommands] lifts an *unquoted* `$(...)` / backtick body into its own segment, but
     * one inside double quotes stays in the segment text, so
     * `echo "$(cat ~/.ssh/id_rsa)"` was invisible to both the allow-list and the credential guard —
     * and the platform shell still runs it. Quoting follows the platform rules: a single-quoted
     * body is literal text, a double-quoted or unquoted one is a command.
     *
     * The check is applied on every platform because the same command line runs through
     * `cmd.exe` on Windows and `sh -c` elsewhere; refusing what `sh` would execute keeps the
     * decision portable.
     */
    private fun substitutionViolation(segment: String, depth: Int): String? {
        if (depth > MAX_INLINE_SCRIPT_DEPTH) {
            return "nested command substitution exceeds $MAX_INLINE_SCRIPT_DEPTH levels — refusing to guess"
        }
        var i = 0
        var inSingleQuote = false
        while (i < segment.length) {
            val c = segment[i]
            when {
                c == '\'' -> {
                    inSingleQuote = !inSingleQuote
                    i++
                }
                inSingleQuote -> i++
                c == '\\' -> i += 2
                // Arithmetic expansion is not a command: `$((x + 1))`.
                c == '$' && segment.startsWith("$((", i) -> {
                    val end = segment.indexOf("))", i)
                    i = if (end < 0) segment.length else end + 2
                }
                c == '$' && i + 1 < segment.length && segment[i + 1] == '(' -> {
                    val end = matchingParen(segment, i + 1)
                    val inner = if (end < 0) segment.substring(i + 2) else segment.substring(i + 2, end)
                    validateSegments(inner, depth + 1)?.let { return "command substitution rejected — $it" }
                    i = if (end < 0) segment.length else end + 1
                }
                c == '`' -> {
                    val end = segment.indexOf('`', i + 1)
                    val inner = if (end < 0) segment.substring(i + 1) else segment.substring(i + 1, end)
                    validateSegments(inner, depth + 1)?.let { return "command substitution rejected — $it" }
                    i = if (end < 0) segment.length else end + 1
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Refuse a process-termination command that would kill the runtime executing this task.
     *
     * The backend JVM is the process running this shell, and the CLI/agent children are
     * part of the same runtime: a task that kills them ends itself with no result. A live
     * run did exactly that — after a navigation failure it ran
     * `taskkill /PID <backend> /F /T`, the backend died, and the task had no report
     * (2026-10-29 Gate, F12). `b4.run` already refuses the CLI's lifecycle commands, so
     * this closes the `coding.shell` route.
     *
     * Only runtime targets are refused: an explicit pid that is not the backend, or a
     * non-runtime image, still passes — killing a stray dev server is legitimate.
     */
    private fun processKillViolation(command: String, tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        val base = normalizeCommandName(tokens[0])
        if (base !in PROCESS_KILL_COMMANDS) return null

        val ownPid = ProcessHandle.current().pid().toString()
        val lower = command.lowercase()
        val pidArguments = PID_ARGUMENT.findAll(command).map { it.groupValues[1] }.toSet()
        val targetsOwnPid = pidArguments.contains(ownPid) ||
            tokens.drop(1).any { it.trim('"', '\'').trimStart('-', '/') == ownPid }
        val targetsRuntimeName = lower.contains("browser4") ||
            Regex("""(?<![\w-])java(?:w)?(?:\.exe)?(?![\w-])""").containsMatchIn(lower)
        if (!targetsOwnPid && !targetsRuntimeName) return null

        return "'$base' would terminate the Browser4 backend (pid $ownPid) or a JVM that may be " +
            "it — coding.shell refuses to kill the runtime executing this task. Stop stray " +
            "processes from outside the task, or name an explicit pid that is not the backend."
    }

    private fun validateSimpleCommand(segment: String, depth: Int): String? {
        val tierViolation = validateSimpleCommandTier(segment, depth)
        if (tierViolation != null) return tierViolation
        // The command itself is permitted: the workspace boundary now applies to its
        // arguments. Checking after the tier rules keeps their specific messages
        // (`mklink` is disabled, credential/protected paths) authoritative.
        return workspaceBoundaryViolation(tokenize(segment))
    }

    private fun validateSimpleCommandTier(segment: String, depth: Int): String? {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return "empty or invalid command"

        // Argument-path guard: the working directory is contained, but nothing stopped an
        // argument like `~/.ssh/id_rsa` from naming a credential location (plan P0-3).
        argumentPathViolation(tokens)?.let { return it }
        // Protected-subtree guard: the file tools refuse `.browser4` / `.github/workflows`, and the
        // shell must not be the way around them.
        protectedPathViolation(tokens)?.let { return it }
        // Command substitutions inside quotes are commands too.
        substitutionViolation(segment, depth)?.let { return it }

        // Skip leading env assignments (VAR=value cmd ...).
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        if (index >= tokens.size) return "empty or invalid command"

        val rawToken = tokens[index]
        val baseCommand = normalizeCommandName(rawToken)
        if (baseCommand.isEmpty()) return "empty or invalid command"

        // Inline script (`sh -c "..."`, `pwsh -Command "..."`, `cmd /c ...`):
        // the wrapper and the inner script must both be validated. Checking only the
        // body let `sh -c "mvn ..."` through the build-only profile, where `sh` is
        // precisely the arbitrary-execution surface BUILD_COMMANDS removes.
        inlineScript(tokens.subList(index, tokens.size))?.let { inner ->
            if (buildOnly && !devToolAllowed(baseCommand)) {
                return "'$baseCommand' is not allowed in the build-only profile " +
                    "(inline-script hosts are not build tools)"
            }
            validateSegments(inner, depth + 1)?.let { violation ->
                return "inline script via '$baseCommand' rejected — $violation"
            }
            return null
        }

        // Absolute vetoes that no flag, allow-list entry or PATH lookup can lift.
        if (baseCommand in FORBIDDEN_COMMANDS) {
            return "'$baseCommand' is disabled — workspace links are provisioned by the framework, " +
                "not by the agent"
        }
        // A task must not destroy the runtime that executes it. `b4.run` already refuses the
        // CLI's own lifecycle commands; `coding.shell` was the remaining way in — a live run
        // ended itself with `taskkill /PID <backend> /F /T` (2026-10-29 Gate, F12).
        processKillViolation(segment, tokens.subList(index, tokens.size))?.let { return it }
        // Explicit deny — absolute veto
        if (baseCommand in explicitDeny) return "'$baseCommand' is explicitly denied"
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(segment)) return "matches deny pattern: ${pattern.pattern}"
        }
        if (baseCommand in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(segment)) return null
        }

        // cmd.exe flow control: valid inside a script, meaningless as a standalone
        // command. Rejected with an actionable route instead of the generic
        // "not allowed": the model can put the block in a .cmd file, and a hint is
        // what stops it from re-phrasing the same `if exist …` four times.
        if (isWindowsHost() && baseCommand in CMD_CONTROL_KEYWORDS) {
            return "'$baseCommand' is a cmd.exe control-flow keyword and coding.shell does not " +
                "execute blocks. Put the block in a script (write `run.cmd`, then `cmd /c run.cmd`) " +
                "or use `python -c \"...\"` for the same logic"
        }
        // `set` (cmd built-in): allowed without arguments or with NAME=value
        // assignments only — `set /p`, `set /a` and other forms are not.
        if (isWindowsHost() && baseCommand == "set") {
            val setArgs = tokens.drop(index + 1)
            val assignmentsOnly = setArgs.isNotEmpty() &&
                setArgs.all { ENV_ASSIGNMENT.matches(it.trim('"')) }
            if (setArgs.isEmpty() || assignmentsOnly) return null
            return "'set' is only allowed with no arguments or with NAME=value assignments " +
                "(e.g. `set PYTHONIOENCODING=utf-8`)"
        }

        // Category checks use the normalized name, so platform launcher
        // suffixes resolve to their tool: `mvnw.cmd` → `mvnw`, `python3.exe` →
        // `python3`. Without this, Windows builds were rejected while the same
        // command passed on Linux (plan P0.4 / eval K4).
        if (baseCommand in SAFE_COMMANDS) return null
        if (devToolAllowed(baseCommand)) return null
        if (allowNetwork && baseCommand in NETWORK_COMMANDS) return null
        if (allowDestructive && baseCommand in DESTRUCTIVE_COMMANDS) return null

        // Network and destructive tools are VETOES, not fall-throughs: the PATH
        // fallback below must not resurrect a tool whose category was disabled.
        // Without this, `curl` (installed on almost every host) was allowed even
        // with allowNetwork=false, which made the whole network tier cosmetic.
        if (baseCommand in NETWORK_COMMANDS) {
            return "'$baseCommand' requires network access but allowNetwork=false"
        }
        if (baseCommand in DESTRUCTIVE_COMMANDS) {
            return "'$baseCommand' is a destructive command but allowDestructive=false"
        }

        // Explicit path form (e.g. /usr/bin/git, ./script.sh, C:\repo\mvnw.cmd):
        // historically allowed with dev tools; an absolute path the operator
        // spelled out is not the injection vector — the *unexpected* token is.
        // Under the build-only profile this fallback is closed, otherwise
        // `./anything.sh` would bypass BUILD_COMMANDS.
        if (isPathLike(rawToken) && allowDevTools && !buildOnly) return null

        // PATH fallback — command not in any set but found on PATH
        if (allowDevTools && !buildOnly && isCommandOnPath(baseCommand)) return null

        return "command '$baseCommand' is not allowed (safe=${baseCommand in SAFE_COMMANDS}, " +
            "dev=${devToolAllowed(baseCommand)}, " +
            "network=${baseCommand in NETWORK_COMMANDS && allowNetwork}, " +
            "destructive=${baseCommand in DESTRUCTIVE_COMMANDS && allowDestructive})"
    }

    /**
     * Split a command line into the simple commands it executes, in order.
     *
     * Handles `&&`, `||`, `;`, `|`, `&`, newlines and command substitution
     * (`$(...)` and backticks, whose bodies are emitted as their own segments).
     * Quoting is respected, so `grep "a|b" file` stays one segment.
     */
    internal fun splitSimpleCommands(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) segments += text
            current.setLength(0)
        }

        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                if (c == quote) quote = null
                current.append(c)
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                    i++
                }
                // Arithmetic expansion is not a command: keep it as text.
                c == '$' && command.startsWith("$((", i) -> {
                    val end = command.indexOf("))", i)
                    val stop = if (end < 0) command.length else end + 2
                    current.append(command, i, stop)
                    i = stop
                }
                // Command substitution — the body is a command of its own.
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    val end = matchingParen(command, i + 1)
                    val inner = if (end < 0) command.substring(i + 2) else command.substring(i + 2, end)
                    segments += splitSimpleCommands(inner)
                    current.append(' ')
                    i = if (end < 0) command.length else end + 1
                }
                c == '`' -> {
                    val end = command.indexOf('`', i + 1)
                    val inner = if (end < 0) command.substring(i + 1) else command.substring(i + 1, end)
                    segments += splitSimpleCommands(inner)
                    current.append(' ')
                    i = if (end < 0) command.length else end + 1
                }
                c == '\n' || c == '\r' -> {
                    flush()
                    i++
                }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flush(); i += 2
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '|' -> {
                    flush(); i += 2
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '&' -> {
                    flush(); i += 2
                }
                // `2>&1`, `1>&2`, `>&1`: the ampersand belongs to a redirection, not to
                // command chaining. Splitting on it turned the descriptor into a command —
                // `python x.py > out.txt 2>&1` was refused with "command '1' is not
                // allowed" (observed 2026-10-28 rerun, 9 times in one run).
                c == '&' && isRedirectionAmpersand(command, i, current) -> {
                    current.append(c)
                    i++
                }
                c == '|' || c == ';' || c == '&' -> {
                    flush(); i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        flush()
        return segments.filter { it.isNotBlank() }
    }

    /**
     * True when the `&` at [index] continues a descriptor redirection (`2>&1`, `>&2`).
     *
     * The splitter treats `&` as command chaining, so `python x.py > out.txt 2>&1` used to
     * become the segments `python x.py > out.txt 2>` and `1` — and `1` is not an allowed
     * command (observed 2026-10-28 rerun: 9 such refusals). A redirection `&` follows a
     * `>`/`<` (optionally with a descriptor digit) and is followed by a descriptor digit
     * or `-`.
     */
    private fun isRedirectionAmpersand(command: String, index: Int, pending: StringBuilder): Boolean {
        val next = command.getOrNull(index + 1) ?: return false
        if (!next.isDigit() && next != '-') return false
        val text = pending.toString().trimEnd()
        if (!text.endsWith(">") && !text.endsWith("<")) return false
        val before = text.dropLast(1)
        // `2>&1` (digit descriptor) or `>&1` (no descriptor); `a > b` has no `&` here anyway.
        return before.isEmpty() || before.last().isDigit() || before.last().isWhitespace()
    }

    /** Index of the `)` matching the `(` at [openIndex], honoring nesting and quotes. */    private fun matchingParen(text: String, openIndex: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '\'', '"' -> quote = c
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * Tokenize one simple command, respecting single/double quotes.
     * Quotes are stripped; the contents become a single token.
     */
    internal fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in command) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * Inner script of an inline shell invocation, or null when [tokens] is not
     * one. `sh|bash|zsh|dash -c <script>`, `pwsh|powershell -c|-Command <script>`,
     * `cmd /c|/k <script>`.
     */
    internal fun inlineScript(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        val base = normalizeCommandName(tokens[0])
        val flags = when (base) {
            "sh", "bash", "zsh", "dash" -> setOf("-c")
            "pwsh", "powershell" -> setOf("-c", "-command", "/c", "/command")
            "cmd" -> setOf("/c", "/k")
            else -> return null
        }
        val flagIndex = tokens.indexOfFirst { it.lowercase() in flags }
        if (flagIndex < 0 || flagIndex + 1 >= tokens.size) return null
        return tokens.subList(flagIndex + 1, tokens.size).joinToString(" ")
    }

    /**
     * Normalized base-command name used for allow-list membership:
     * basename, lowercase, without a Windows launcher / script suffix
     * (`mvnw.cmd` → `mvnw`, `python3.exe` → `python3`, `/usr/bin/git` → `git`).
     */
    internal fun normalizeCommandName(token: String): String {
        var name = token.trim().trim('\'', '"', '\\')
        if (name.isEmpty()) return ""
        name = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        for (suffix in EXECUTABLE_SUFFIXES) {
            if (name.endsWith(suffix) && name.length > suffix.length) {
                name = name.removeSuffix(suffix)
                break
            }
        }
        return name
    }

    /** True when the raw token is spelled as a path rather than a bare name. */
    private fun isPathLike(token: String): Boolean {
        val t = token.trim().trim('\'', '"')
        return t.startsWith("/") || t.startsWith("./") || t.startsWith("../") ||
            t.startsWith(".\\") || t.startsWith("..\\") || t.isAbsolutePath() ||
            t.contains('/') || t.contains('\\')
    }

    private fun String.isAbsolutePath(): Boolean =
        length >= 3 && this[1] == ':' && (this[2] == '\\' || this[2] == '/')

    private fun extractBaseCommand(command: String): String {
        val segments = splitSimpleCommands(command)
        val first = segments.firstOrNull() ?: return ""
        val tokens = tokenize(first)
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        if (index >= tokens.size) return ""
        return normalizeCommandName(tokens[index])
    }

    private fun isCommandOnPath(command: String): Boolean {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val checkCmd = if (isWindows) "where $command" else "which $command"
            val pb = if (isWindows) ProcessBuilder("cmd.exe", "/c", checkCmd)
            else ProcessBuilder("sh", "-c", checkCmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Command execution
    // ------------------------------------------------------------------

    private fun runCommand(
        sessionId: String,
        command: String,
        timeoutSeconds: Long,
        workDir: File,
        extraEnv: Map<String, String>,
    ): ShellResult {
        val startTime = System.currentTimeMillis()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        val processBuilder = if (isWindows) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }

        processBuilder.directory(workDir)
        processBuilder.redirectErrorStream(false)

        // Merge session env + command env into process environment
        if (sessionEnv.isNotEmpty() || extraEnv.isNotEmpty()) {
            val env = processBuilder.environment()
            env.putAll(sessionEnv)
            env.putAll(extraEnv)
        }

        val process = processBuilder.start()

        // Decode the raw bytes ourselves: the stream's default charset is the JVM
        // default (UTF-8), which turns cmd.exe's code-page output into U+FFFD.
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.use { decodeProcessOutput(it.readBytes()) }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.use { decodeProcessOutput(it.readBytes()) }
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val durationMs = System.currentTimeMillis() - startTime

        if (!completed) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            val stdout = stdoutFuture.getNow("")
            val stderr = stderrFuture.getNow("")
            return ShellResult(
                sessionId = sessionId,
                command = command,
                exitCode = -1,
                stdout = truncateOutput(stdout, command),
                stderr = truncateOutput(stderr, command),
                durationMs = durationMs,
                timedOut = true,
            )
        }

        val stdout = stdoutFuture.get()
        val stderr = stderrFuture.get()

        return ShellResult(
            sessionId = sessionId,
            command = command,
            exitCode = process.exitValue(),
            stdout = truncateOutput(stdout, command),
            stderr = truncateOutput(stderr, command),
            durationMs = durationMs,
        )
    }

    private fun isDiagnosticOutputCommand(command: String): Boolean {
        for (segment in splitSimpleCommands(command)) {
            val tokens = tokenize(segment)
            var index = 0
            while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
            if (index >= tokens.size) continue
            if (DIAGNOSTIC_COMMAND.containsMatchIn(tokens[index])) return true
        }
        return false
    }

    /**
     * Bound the captured output.
     *
     * Cut on code-point boundaries ([SafeText]): a plain `take`/`takeLast` can
     * split a surrogate pair, and the resulting lone surrogate is not encodable
     * as UTF-8 — it used to poison every later prompt/trace write of the run.
     */
    internal fun truncateOutput(output: String, command: String): String {
        if (output.length <= MAX_OUTPUT_CHARS) return output
        // Compilers, tests and linters put the verdict — and the errors that
        // matter — at the END of the output. Head-only truncation kept the
        // banner and dropped the diagnostics (plan P1.5).
        if (isDiagnosticOutputCommand(command)) {
            val headLen = DIAGNOSTIC_HEAD_CHARS.coerceAtMost(MAX_OUTPUT_CHARS / 4)
            val tailLen = MAX_OUTPUT_CHARS - headLen
            return SafeText.take(output, headLen) +
                "\n... (output truncated: kept the first $headLen and the last $tailLen of ${output.length} chars) ...\n" +
                SafeText.takeLast(output, tailLen)
        }
        return SafeText.take(output, MAX_OUTPUT_CHARS) + "\n... (output truncated at $MAX_OUTPUT_CHARS chars) ..."
    }

    private fun formatResult(result: ShellResult): String {
        val status = if (result.success) "SUCCESS" else "FAILED"
        return buildString {
            appendLine("Session: ${result.sessionId}")
            appendLine("Status: $status")
            appendLine("Exit Code: ${result.exitCode}")
            appendLine("Duration: ${result.durationMs}ms")
            if (result.timedOut) appendLine("⚠️ Command timed out")
            if (result.stdout.isNotBlank()) {
                appendLine("--- stdout ---")
                appendLine(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(result.stderr)
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------
    // Convenience methods
    // ------------------------------------------------------------------

    /**
     * Execute a git command in the workspace.
     */
    suspend fun git(args: String, timeoutSeconds: Long = 60): String =
        execute("git $args", timeoutSeconds)

    /**
     * Check if a command/program is available.
     */
    fun isAvailable(command: String): Boolean = isCommandOnPath(command)

    /**
     * List available development tools detected on PATH.
     *
     * Reports the tier this instance actually runs at, so `coding.toolsDetect` cannot advertise
     * interpreters to a build-only shell.
     */
    fun detectAvailableTools(): Set<String> {
        val tier = if (buildOnly) BUILD_COMMANDS else DEV_COMMANDS
        return tier.filter { isCommandOnPath(it) }.toSet()
    }

    /**
     * Detect the project type in the workspace by looking for build files.
     */
    fun detectProjectType(): String {
        val files = mutableListOf<String>()
        try {
            java.nio.file.Files.walk(canonicalBaseDir, 2).use { stream ->
                stream.filter { !java.nio.file.Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .forEach { files.add(it) }
            }
        } catch (_: Exception) {}

        return buildString {
            if (files.any { it == "Cargo.toml" }) append("rust, ")
            if (files.any { it == "pom.xml" }) append("maven, ")
            if (files.any { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }) append("gradle, ")
            if (files.any { it == "package.json" }) append("node, ")
            if (files.any { it == "pyproject.toml" || it == "setup.py" }) append("python, ")
            if (files.any { it == "go.mod" }) append("go, ")
            if (files.any { it.endsWith(".sln") || it.endsWith(".csproj") }) append("dotnet, ")
            if (files.any { it == "CMakeLists.txt" }) append("cmake, ")
            if (files.any { it == "Makefile" }) append("make, ")
            if (files.any { it == "Dockerfile" }) append("docker, ")
        }.removeSuffix(", ").ifEmpty { "unknown" }
    }
}

// ---------------------------------------------------------------------------
// v0.3 structured exceptions — the shell's typed answer to "where am I allowed
// to write?" Replaces the previous `null` / silent-fallback ambiguity so
// S1/S2 callers (CodingToolExecutor, ToolExecutionCoordinator) can emit a
// stable "[fail]" without string-matching.
// ---------------------------------------------------------------------------

/** The requested working directory is lexically outside the shell's allowed
 *  root (and [allowExternalAccess] is false). The message carries both paths
 *  so the caller can surface a actionable hint. */
class WorkspaceEscapeException(
    val requested: Path,
    val allowedRoot: Path,
) : IllegalArgumentException(
    "working directory ${requested.toAbsolutePath().normalize()} is outside the allowed workspace " +
    "${allowedRoot.toAbsolutePath().normalize()}; set -Dbrowser4.agent.allowExternalAccess=true to bypass"
)

/** The requested working directory does not exist, is not a directory, or
 *  could not be created. */
class WorkspaceDirMissingException(
    val path: Path,
    detail: String? = null,
) : IllegalArgumentException(
    buildString {
        append("working directory does not exist: ")
        append(path.toAbsolutePath().normalize())
        if (detail != null) append(" (").append(detail).append(')')
    }
)

/**
 * The requested working directory is inside a workspace-protected subtree
 * ([CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS]).
 *
 * The message is a structured refusal so the caller can surface the same
 * `PERMISSION_DENIED | code=…` contract the rest of the policy uses.
 */
class WorkspaceProtectedPathException(
    val path: Path,
    val protectedDir: String,
) : IllegalArgumentException(
    PermissionAudit.format(
        tool = "coding.shell",
        code = PermissionAudit.CODE_PROTECTED_PATH,
        detail = "working directory ${path.toAbsolutePath().normalize()} is inside protected " +
            "directory '$protectedDir'",
        hint = "use the workspace root or a subdirectory outside '$protectedDir'",
    )
)



