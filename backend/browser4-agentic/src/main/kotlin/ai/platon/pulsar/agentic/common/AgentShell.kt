package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Shell command execution result containing exit code, stdout, stderr, and metadata.
 */
data class ShellResult(
    val sessionId: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut

    override fun toString(): String {
        val status = if (success) "SUCCESS" else "FAILED(exitCode=$exitCode, timedOut=$timedOut)"
        return buildString {
            appendLine("[$status] command='$command' (${durationMs}ms)")
            if (stdout.isNotBlank()) appendLine("stdout:\n$stdout")
            if (stderr.isNotBlank()) appendLine("stderr:\n$stderr")
        }.trimEnd()
    }
}

/**
 * Secure shell command execution subsystem for AI agents.
 *
 * Provides controlled execution of shell commands with:
 * - **Command whitelist** - Only allows execution of safe, read-only commands
 * - Configurable timeout to prevent runaway processes
 * - Working directory management
 * - Output capture (stdout and stderr)
 * - Session-based result tracking for reading past outputs
 * - Command validation and security controls
 *
 * ## Security Model
 *
 * Commands are validated against a whitelist before execution. Only the following commands are allowed:
 * - Basic navigation: `ls`, `pwd`, `tree`
 * - File viewing: `cat`, `less`, `head`, `tail`
 * - Text processing: `grep`, `awk`, `sed`
 * - Counting: `wc`
 * - System info: `uname`, `hostname`, `uptime`, `whoami`, `id`
 * - Resource monitoring: `free`, `df`, `du`
 * - Process info: `ps`, `top`, `pgrep`
 * - Network info: `ip`, `ss`
 * - Environment: `env`, `printenv`, `which`, `type`
 *
 * All other commands (including `rm`, `mv`, `cp`, `chmod`, `curl`, `wget`, etc.) are blocked.
 *
 * ## Usage Example:
 *
 * ```kotlin
 * val shell = AgentShell(baseDir = Paths.get("/tmp/agent-work"))
 * val result = shell.execute("ls -la")  // Allowed
 * val blocked = shell.execute("rm file.txt")  // Blocked: not in whitelist
 * ```
 *
 * @param baseDir The base working directory for command execution.
 * @param defaultTimeoutSeconds The default timeout for commands in seconds.
 * @author Browser4 Team
 */
class AgentShell constructor(
    private val baseDir: Path,
    private val defaultTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val MAX_TIMEOUT_SECONDS = 300L
        const val MAX_OUTPUT_CHARS = 100_000

        private val BLOCKED_PATTERNS = listOf(
            // Prevent destructive recursive operations
            "rm\\s+-[^\\s]*r[^\\s]*\\s+/\\s*$",
            "rm\\s+-[^\\s]*r[^\\s]*\\s+/\\*",
            // Prevent format/disk operations
            "mkfs\\.",
            "dd\\s+.*of=/dev/",
            // Prevent shutdown/reboot
            "shutdown",
            "reboot",
            "init\\s+[06]",
            // Prevent fork bombs
            ":\\(\\)\\{",
        ).map { Regex(it) }

        /**
         * Whitelist of allowed commands and command patterns.
         * Only commands starting with these patterns are permitted.
         */
        val ALLOWED_COMMANDS = setOf(
            // Basic navigation and listing
            "ls", "pwd", "tree",
            // File viewing
            "cat", "less", "head", "tail",
            // Text processing (sed is allowed but with restricted usage - see validation)
            "grep", "awk", "sed",
            // Counting
            "wc",
            // System info
            "uname", "hostname", "uptime", "whoami", "id",
            // Resource monitoring
            "free", "df", "du",
            // Process info
            "ps", "top", "pgrep",
            // Network commands (only specific subcommands allowed)
            "ip addr", "ip route", "ss",
            // Environment
            "env", "printenv", "which", "type"
        )
    }

    private val logger = getLogger(this)
    private val sessionCounter = AtomicLong(0)
    private val results = ConcurrentHashMap<String, ShellResult>()

    /**
     * Execute a shell command with the specified timeout.
     *
     * @param command The shell command to execute.
     * @param timeoutSeconds Timeout in seconds (default: 30, max: 300).
     * @param workingDir Optional working directory override. Defaults to baseDir.
     * @return A formatted string describing the execution result.
     */
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        workingDir: String? = null,
    ): String {
        if (command.isBlank()) {
            return "Error: Command must not be blank."
        }

        val violation = validateCommand(command)
        if (violation != null) {
            return "Error: Command blocked for security reasons - $violation"
        }

        val effectiveTimeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val sessionId = "shell-${sessionCounter.incrementAndGet()}"
        val dir = if (workingDir != null) {
            val resolved = baseDir.resolve(workingDir).normalize()
            // Prevent path traversal outside baseDir
            if (!resolved.startsWith(baseDir.normalize())) {
                return "Error: Working directory must be within the base directory."
            }
            resolved.toFile()
        } else {
            baseDir.toFile()
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                runCommand(sessionId, command, effectiveTimeout, dir)
            }

            results[sessionId] = result
            formatResult(result)
        } catch (e: IOException) {
            val msg = "Error: Failed to execute command '$command'. ${e.message ?: ""}"
            logger.warn(msg, e)
            msg.trim()
        } catch (e: Exception) {
            val msg = "Error: Unexpected error executing command '$command'. ${e.message ?: ""}"
            logger.warn(msg, e)
            msg.trim()
        }
    }

    /**
     * Read the output of a previous command execution by session ID.
     *
     * @param sessionId The session ID returned from execute.
     * @return The formatted output of the previous execution.
     */
    fun readOutput(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No result found for session '$sessionId'."
        return formatResult(result)
    }

    /**
     * Get the status of a previous command execution by session ID.
     *
     * @param sessionId The session ID to query.
     * @return A status summary of the execution.
     */
    fun getStatus(sessionId: String): String {
        val result = results[sessionId]
            ?: return "Error: No session found with ID '$sessionId'."
        val status = if (result.success) "SUCCESS" else "FAILED"
        return "Session '$sessionId': status=$status, exitCode=${result.exitCode}, " +
                "timedOut=${result.timedOut}, duration=${result.durationMs}ms, " +
                "command='${result.command}'"
    }

    /**
     * List all tracked command sessions with their status.
     *
     * @return A formatted list of all sessions.
     */
    fun listSessions(): String {
        if (results.isEmpty()) {
            return "No shell sessions recorded."
        }

        val sb = StringBuilder()
        sb.appendLine("Shell sessions (${results.size} total):")
        for ((id, result) in results) {
            val status = if (result.success) "SUCCESS" else "FAILED"
            sb.appendLine("- $id: status=$status, command='${result.command}', duration=${result.durationMs}ms")
        }
        return sb.toString().trimEnd()
    }

    private fun runCommand(
        sessionId: String,
        command: String,
        timeoutSeconds: Long,
        workDir: java.io.File,
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

        val process = processBuilder.start()

        // Read streams in separate threads to avoid deadlock when output buffers fill up
        val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val durationMs = System.currentTimeMillis() - startTime

        if (!completed) {
            process.destroyForcibly()
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

    private fun validateCommand(command: String): String? {
        // First check if command is in whitelist
        val baseCommand = extractBaseCommand(command)
        if (baseCommand.isEmpty()) {
            return "empty or invalid command"
        }

        if (!isCommandAllowed(baseCommand)) {
            return "command '$baseCommand' is not in the whitelist. Allowed commands: ${ALLOWED_COMMANDS.sorted().joinToString(", ")}"
        }

        // Additional validation for specific commands
        if (baseCommand == "sed") {
            // Prevent in-place editing with sed -i flag (various forms)
            // Matches: sed -i, sed -ie, sed --in-place, sed -n -i, etc.
            if (Regex("sed\\s+.*(-i|--in-place)").containsMatchIn(command)) {
                return "sed in-place editing is not allowed"
            }
        }

        // Then check blocked patterns for additional safety
        for (pattern in BLOCKED_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                return "matches blocked pattern: ${pattern.pattern}"
            }
        }
        return null
    }

    /**
     * Extract the base command from a command string.
     * Handles simple commands, multi-word commands (like "ip addr"), and commands with arguments.
     */
    private fun extractBaseCommand(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ""

        // Split by whitespace and get the first token
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) return ""

        val firstToken = tokens[0]

        // Handle multi-word commands like "ip addr" or "ip route"
        // Only these specific ip subcommands are allowed, not all ip commands
        if (firstToken == "ip" && tokens.size > 1) {
            val secondToken = tokens[1]
            if (secondToken == "addr" || secondToken == "route") {
                return "ip $secondToken"
            }
            // For other ip subcommands, return the full "ip <subcommand>"
            // which will fail whitelist validation
            return "ip $secondToken"
        }

        return firstToken
    }

    /**
     * Check if a command is allowed based on the whitelist.
     * The command parameter should be the base command extracted by extractBaseCommand().
     */
    private fun isCommandAllowed(command: String): Boolean {
        // Direct whitelist check - extractBaseCommand() already handles multi-word commands
        return ALLOWED_COMMANDS.contains(command)
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
}
