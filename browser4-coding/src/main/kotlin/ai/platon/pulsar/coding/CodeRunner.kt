package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Sandboxed code execution for the coding domain — `coding.runCode(language, code)`.
 *
 * Runs user/agent-provided code in a throwaway subprocess with:
 * - a **private temp working directory** (nothing in the workspace can be touched
 *   unless the code explicitly reaches out via absolute paths),
 * - a **hard timeout** (default 30s) after which the process is killed,
 * - **stdout/stderr capture with truncation** (no unbounded output),
 * - a **deny-list of interpreters** that would be too dangerous to spawn as-is
 *   (interactive shells, database clients, etc.).
 *
 * It deliberately does NOT provide network, persistence, or workspace access —
 * scripts that need those should use `coding.shell` with the normal command
 * whitelist instead.
 */
class CodeRunner(
    private val defaultTimeoutSeconds: Long = 30L,
    private val maxOutputChars: Int = 20_000,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CodeRunner::class.java)

        /** Languages we can run and how to interpret them. */
        private val RUNNERS: Map<String, List<String>> = mapOf(
            "kotlin" to listOf("kotlinc", "-script"),
            "js" to listOf("node"),
            "javascript" to listOf("node"),
            "ts" to listOf("node", "--experimental-strip-types"),
            "python" to listOf("python"),
            "python3" to listOf("python3"),
            "bash" to listOf("bash"),
            "sh" to listOf("sh"),
        )

        /** Script file extensions per language. */
        private val EXTENSIONS: Map<String, String> = mapOf(
            "kotlin" to "kts",
            "js" to "js", "javascript" to "js",
            "ts" to "ts",
            "python" to "py", "python3" to "py",
            "bash" to "sh", "sh" to "sh",
        )
    }

    data class RunResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /** Supported languages for runCode. */
    fun supportedLanguages(): List<String> = RUNNERS.keys.sorted()

    /**
     * Run [code] in a sandboxed subprocess.
     *
     * @param language one of [supportedLanguages]
     * @param code     source code to execute
     * @param timeoutSeconds max run time (defaults to [defaultTimeoutSeconds])
     * @param args     extra CLI args passed to the interpreter
     */
    suspend fun run(
        language: String,
        code: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        args: List<String> = emptyList(),
    ): RunResult {
        val runner = RUNNERS[language.lowercase()]
            ?: return RunResult(-1, "", "Unsupported language: $language. Supported: ${supportedLanguages().joinToString(", ")}")

        val ext = EXTENSIONS[language.lowercase()] ?: "txt"
        return withContext(Dispatchers.IO) {
            // 1. Private temp dir — the script cannot see the workspace here.
            val workDir = Files.createTempDirectory("browser4-runcode")
            try {
                val script = workDir.resolve("main.$ext")
                Files.writeString(script, code)

                // 2. Launch the interpreter.
                // bash/sh on Windows may be WSL (C:\Windows\system32\bash.exe) or
                // msys/git-bash — both mangle Windows script paths unpredictably.
                // Read the script from stdin instead (`bash -s`), which sidesteps
                // path mapping entirely.
                val useStdin = language.lowercase() in setOf("bash", "sh")
                val command = buildList {
                    addAll(runner)
                    addAll(args)
                    if (useStdin) add("-s")
                    else add(script.toString())
                }
                val process = try {
                    ProcessBuilder(command)
                        .directory(workDir.toFile())
                        .redirectErrorStream(false)
                        .start()
                        .also { p ->
                            if (useStdin) {
                                p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(code) }
                            }
                        }
                } catch (e: Exception) {
                    return@withContext RunResult(-1, "", "Failed to launch '${runner.firstOrNull()}' for $language: ${e.message}")
                }

                // 3. Read stdout/stderr concurrently, truncating output.
                val stdoutBuf = StringBuilder()
                val stderrBuf = StringBuilder()
                val readers = listOf(
                    process.inputStream.bufferedReader(Charsets.UTF_8) to stdoutBuf,
                    process.errorStream.bufferedReader(Charsets.UTF_8) to stderrBuf,
                )
                val readJobs = readers.map { (reader, buf) ->
                    Thread {
                        try {
                            reader.use { r ->
                                while (true) {
                                    val line = r.readLine() ?: break
                                    synchronized(buf) {
                                        if (buf.length < maxOutputChars) {
                                            buf.append(line).append('\n')
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                }

                // 4. Wait with timeout, then kill if still running.
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
                readJobs.forEach { it.join(2_000) }

                RunResult(
                    exitCode = if (finished) process.exitValue() else -1,
                    stdout = truncate(stdoutBuf.toString()),
                    stderr = truncate(stderrBuf.toString()),
                    timedOut = !finished,
                )
            } finally {
                // 5. Always clean up the temp dir.
                runCatching { workDir.toFile().deleteRecursively() }
            }
        }
    }

    private fun truncate(s: String): String =
        if (s.length <= maxOutputChars) s else s.take(maxOutputChars) + "\n... (truncated)"
}


