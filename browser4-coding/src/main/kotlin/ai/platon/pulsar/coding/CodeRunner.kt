package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes
import ai.platon.pulsar.coding.sandbox.ProcessLauncher
import ai.platon.pulsar.coding.sandbox.ProcessSpec
import ai.platon.pulsar.coding.sandbox.SandboxDenials
import ai.platon.pulsar.coding.sandbox.SandboxEnvironment
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
 * It provides no workspace access and no persistence; the network flag is the caller's
 * decision ([networkAllowed]), so a policy carrying an active `net.connect` grant can turn it
 * on. Scripts that need the workspace should use `coding.shell` with the normal command
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
     * The interpreter program that would run [language], or `null` for an unsupported language.
     *
     * Callers use this to check an operator grant against the program name (the same granularity
     * the command rail approves) before handing the code to [run].
     */
    fun interpreterFor(language: String): String? = RUNNERS[language.lowercase()]?.firstOrNull()

    /**
     * Run [code] in a sandboxed subprocess.
     *
     * @param language one of [supportedLanguages]
     * @param code     source code to execute
     * @param timeoutSeconds max run time (defaults to [defaultTimeoutSeconds])
     * @param args     extra CLI args passed to the interpreter
     * @param networkAllowed whether the sandbox may let the process reach the network; supplied by
     *   the caller from the access policy, since this runner has no policy of its own
     */
    suspend fun run(
        language: String,
        code: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        args: List<String> = emptyList(),
        networkAllowed: Boolean = false,
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
                    // C1/C2: same environment construction and sandbox seam as `coding.shell`.
                    // runCode declares no explicit variables, so it receives the whitelist only.
                    val environment = SandboxEnvironment.build(System.getenv())
                    val spec = ExecutionSandboxes.current().prepare(
                        ProcessSpec(command, workDir, environment),
                        workDir,
                        allowNetwork = networkAllowed,
                    )
                    ProcessLauncher.start(spec, redirectErrorStream = false)
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

                val stdout = truncate(stdoutBuf.toString())
                val stderr = truncate(stderrBuf.toString())
                val exitCode = if (finished) process.exitValue() else -1
                // Plan P3-3: the same structured refusal the shell emits; the raw bytes are logged
                // by the translator rather than handed to the model.
                val denial = if (finished && exitCode != 0) {
                    SandboxDenials.envelope("coding.runCode", "$language script", stderr + "\n" + stdout)
                } else {
                    null
                }

                RunResult(
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = denial ?: stderr,
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


