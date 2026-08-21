package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
            // Shell scripting
            "bash", "sh", "zsh", "powershell", "pwsh",
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
            "ln", "mklink", "mount",
            "dd", "mkfs", "fdisk",
            "kill", "killall", "pkill", "taskkill",
            "shutdown", "reboot",
            "systemctl", "service", "sc",
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
     * Show the current effective policy for a command — why it's allowed or denied.
     * Returns a human-readable explanation.
     */
    fun explain(command: String): String {
        val base = extractBaseCommand(command).lowercase()

        // Check blocked patterns
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return "'$command' is BLOCKED — matches hardcoded blocked pattern: ${pattern.pattern}"
            }
        }

        // Deny patterns
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(command)) {
                return "'$command' is DENIED — matches deny pattern: ${pattern.pattern}"
            }
        }

        // Explicit deny
        if (base in explicitDeny) {
            return "'$command' is DENIED — '$base' is in the explicit deny list"
        }

        // Explicit allow
        if (base in explicitAllow) {
            return "'$command' is ALLOWED — '$base' is in the explicit allow list"
        }

        // Allow patterns
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(command)) {
                return "'$command' is ALLOWED — matches allow pattern: ${pattern.pattern}"
            }
        }

        // Category checks
        if (base in SAFE_COMMANDS) return "'$command' is ALLOWED — '$base' is a safe command"
        if (allowDevTools && base in DEV_COMMANDS) return "'$command' is ALLOWED — '$base' is a dev tool (allowDevTools=true)"
        if (allowNetwork && base in NETWORK_COMMANDS) return "'$command' is ALLOWED — '$base' is a network command (allowNetwork=true)"
        if (allowDestructive && base in DESTRUCTIVE_COMMANDS) return "'$command' is ALLOWED — '$base' is a destructive command (allowDestructive=true)"

        if (command.startsWith("/") || command.startsWith("./") || command.startsWith(".\\")) {
            return if (allowDevTools) "'$command' is ALLOWED — full-path command (allowDevTools=true)"
            else "'$command' is DENIED — full-path commands require allowDevTools=true"
        }

        if (allowDevTools && isCommandOnPath(base)) {
            return "'$command' is ALLOWED — '$base' found on PATH (allowDevTools=true)"
        }

        return "'$command' is DENIED — '$base' not in any allowed category"
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

    private fun validateCommand(command: String): String? {
        val baseCommand = extractBaseCommand(command).lowercase()
        if (baseCommand.isEmpty()) return "empty or invalid command"

        // 1. Hardcoded blocked patterns — always checked first, cannot be overridden
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return "matches blocked pattern: ${pattern.pattern}"
            }
        }

        // 2. Explicit deny — absolute veto (except blocked patterns above)
        if (baseCommand in explicitDeny) {
            return "'$baseCommand' is explicitly denied"
        }

        // 3. Deny patterns — regex-based veto
        for (pattern in denyPatterns) {
            if (pattern.containsMatchIn(command)) {
                return "matches deny pattern: ${pattern.pattern}"
            }
        }

        // 4. Explicit allow — bypasses category checks entirely
        if (baseCommand in explicitAllow) return null

        // 5. Allow patterns — bypasses category checks
        for (pattern in allowPatterns) {
            if (pattern.containsMatchIn(command)) return null
        }

        // 6. Category-based checks
        if (baseCommand in SAFE_COMMANDS) return null
        if (allowDevTools && (baseCommand in DEV_COMMANDS)) return null
        if (allowNetwork && (baseCommand in NETWORK_COMMANDS)) return null
        if (allowDestructive && (baseCommand in DESTRUCTIVE_COMMANDS)) return null

        // 7. Full-path commands (e.g. /usr/bin/git, ./script.sh, .\tool.exe)
        if (command.startsWith("/") || command.startsWith("./") || command.startsWith(".\\")) {
            if (allowDevTools) return null
        }

        // 8. PATH fallback — command not in any set but found on PATH
        if (allowDevTools && isCommandOnPath(baseCommand)) {
            return null
        }

        return "command '$baseCommand' is not allowed (safe=${baseCommand in SAFE_COMMANDS}, " +
            "dev=${baseCommand in DEV_COMMANDS && allowDevTools}, " +
            "network=${baseCommand in NETWORK_COMMANDS && allowNetwork}, " +
            "destructive=${baseCommand in DESTRUCTIVE_COMMANDS && allowDestructive})"
    }

    private fun extractBaseCommand(command: String): String {
        val trimmed = command.trim().trimStart('\\')
        if (trimmed.isEmpty()) return ""

        // Handle quoted commands
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            val endQuote = trimmed.indexOf(trimmed[0], 1)
            if (endQuote > 0) return trimmed.substring(1, endQuote)
        }

        // Strip env var assignments: VAR=val command
        val afterEnv = trimmed.replace(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*?\\s+"), "")

        val tokens = afterEnv.split(Regex("\\s+"))
        if (tokens.isEmpty()) return ""

        val firstToken = tokens[0]

        // Multi-word commands
        if (firstToken == "ip" && tokens.size > 1) return "ip ${tokens[1]}"
        if (firstToken == "docker" && tokens.size > 1) return "docker-${tokens[1]}"

        return firstToken
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

        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
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
                stdout = truncateOutput(stdout),
                stderr = truncateOutput(stderr),
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
            stdout = truncateOutput(stdout),
            stderr = truncateOutput(stderr),
            durationMs = durationMs,
        )
    }

    private fun truncateOutput(output: String): String {
        return if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) + "\n... (output truncated at $MAX_OUTPUT_CHARS chars)"
        } else {
            output
        }
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


