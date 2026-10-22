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
) {
    companion object {
        const val MAX_TIMEOUT_SECONDS = 600L
        const val MAX_OUTPUT_CHARS = 200_000
        private val logger = LoggerFactory.getLogger(CodingAgentShell::class.java)

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
            "cat", "type", "less", "head", "tail",
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
        if (command.isBlank()) {
            return "Error: Command must not be blank."
        }

        val violation = validateCommand(command)
        if (violation != null) {
            return "Error: Command blocked — $violation"
        }

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"

        val dir = resolveWorkingDir(workingDir)
        if (dir == null) {
            return "Error: Working directory not found or not accessible."
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, command, effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            formatResult(result)
        } catch (e: IOException) {
            "Error: I/O failure for '$command' — ${e.message ?: ""}"
        } catch (e: SecurityException) {
            "Error: Permission denied for '$command' — ${e.message ?: ""}"
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
        if (command.isBlank()) {
            return ShellResult("", command, -1, "", "Command must not be blank.", 0)
        }

        val violation = validateCommand(command)
        if (violation != null) {
            return ShellResult("", command, -1, "", "Command blocked: $violation", 0)
        }

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "devshell-${sessionCounter.incrementAndGet()}"
        val dir = resolveWorkingDir(workingDir) ?: canonicalBaseDir.toFile()

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, command, effectiveTimeout, dir, env)
            }
            results[sessionId] = result
            result
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
            allowDevTools && base in DEV_COMMANDS -> "'$base' is a dev tool (allowDevTools=true)"
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
    // Working directory resolution
    // ------------------------------------------------------------------

    private fun resolveWorkingDir(workingDir: String?): File? {
        if (workingDir == null) {
            val dir = canonicalBaseDir.toFile()
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        val absolutePath = Path.of(workingDir)
        val resolved = if (absolutePath.isAbsolute) {
            absolutePath.normalize()
        } else {
            canonicalBaseDir.resolve(workingDir).normalize()
        }

        val dir = resolved.toFile()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    // ------------------------------------------------------------------
    // Command validation
    // ------------------------------------------------------------------

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
        if (command.isBlank()) return "empty or invalid command"

        // 1. Hardcoded blocked patterns — whole string, cannot be overridden
        blockedPatternViolation(command)?.let { return it }

        // 2. Explicit deny / deny patterns — whole-string vetoes (they are
        //    authored as full-command regexes), re-checked per segment below
        extractBaseCommand(command).lowercase().let { base ->
            if (base.isNotEmpty() && base in explicitDeny) return "'$base' is explicitly denied"
        }
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(command)) return "matches deny pattern: ${pattern.pattern}"
        }

        // 3. Explicit allow / allow patterns — whole-string opt-ins bypass the
        //    per-token checks (the operator authored a trust rule for this shape)
        if (extractBaseCommand(command).lowercase() in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(command)) return null
        }

        // 4. Every simple command in the pipeline / substitution / inline script
        return validateSegments(command, depth = 0)
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

    private fun validateSimpleCommand(segment: String, depth: Int): String? {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return "empty or invalid command"

        // Skip leading env assignments (VAR=value cmd ...).
        var index = 0
        while (index < tokens.size && ENV_ASSIGNMENT.matches(tokens[index])) index++
        if (index >= tokens.size) return "empty or invalid command"

        val rawToken = tokens[index]
        val baseCommand = normalizeCommandName(rawToken)
        if (baseCommand.isEmpty()) return "empty or invalid command"

        // Inline script (`sh -c "..."`, `pwsh -Command "..."`, `cmd /c ...`):
        // the wrapper itself is allowed, but the inner script must be validated.
        inlineScript(tokens.subList(index, tokens.size))?.let { inner ->
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
        // Explicit deny — absolute veto
        if (baseCommand in explicitDeny) return "'$baseCommand' is explicitly denied"
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(segment)) return "matches deny pattern: ${pattern.pattern}"
        }
        if (baseCommand in explicitAllow) return null
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(segment)) return null
        }

        // Category checks use the normalized name, so platform launcher
        // suffixes resolve to their tool: `mvnw.cmd` → `mvnw`, `python3.exe` →
        // `python3`. Without this, Windows builds were rejected while the same
        // command passed on Linux (plan P0.4 / eval K4).
        if (baseCommand in SAFE_COMMANDS) return null
        if (allowDevTools && baseCommand in DEV_COMMANDS) return null
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
        if (isPathLike(rawToken) && allowDevTools) return null

        // PATH fallback — command not in any set but found on PATH
        if (allowDevTools && isCommandOnPath(baseCommand)) return null

        return "command '$baseCommand' is not allowed (safe=${baseCommand in SAFE_COMMANDS}, " +
            "dev=${baseCommand in DEV_COMMANDS && allowDevTools}, " +
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

    /** Index of the `)` matching the `(` at [openIndex], honoring nesting and quotes. */
    private fun matchingParen(text: String, openIndex: Int): Int {
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
     */
    fun detectAvailableTools(): Set<String> {
        return DEV_COMMANDS.filter { isCommandOnPath(it) }.toSet()
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


