package ai.platon.pulsar.agentic.cli

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.agentic.observability.CliMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-management configuration. Defaults are materialised here (not hidden
 * behind `?? default` at call sites) and overridable per manager instance.
 */
data class CliProcessConfig(
    val defaultTimeoutMs: Long = 120_000,
    val maxTimeoutMs: Long = 600_000,
    val graceMs: Long = 3_000,
    val outputBufferBytes: Int = 1 shl 20,
    val returnMaxTokens: Int = 10_000,
    val maxConcurrentGlobal: Int = 8,
    val maxConcurrentPerSession: Int = 2,
    val queueWaitMs: Long = 30_000,
    val backendHealthTimeoutMs: Long = 2_000,
    /** M0 conclusion: never let the CLI auto-start/restart the backend. */
    val allowServerAutoStart: Boolean = false,
)

/**
 * Accurate subprocess invocation, monitoring and management for `cli.run`
 * (design §4.1). Three-layer seam: tool layer → [CliProcessManager] (resolve +
 * run) → OS subprocess.
 *
 * Key semantics:
 * - request/spec separation: [resolve] fills explicit defaults and caps;
 * - deadline fusion: timeout vs (token) cancellation are mutually exclusive —
 *   this layer owns the deadline and attributes the cause;
 * - error semantics: [CliResult.infraFailure] only for infrastructure failures;
 *   non-zero exit / timeout / abort resolve into normal results;
 * - env whitelist: child env is cleared and only allowlisted variables injected;
 * - tree kill: SIGTERM → grace → SIGKILL on POSIX, `taskkill /T /F` on Windows;
 * - backend pre-check: refuses to run when the backend is unreachable, so the
 *   CLI can never auto-start a server (M0).
 *
 * Platform limits: PDEATHSIG / Job Object need native support and are out of
 * scope for M1; parent-death cleanup is covered by teardown (shutdown hook /
 * agent close) instead.
 */
class CliProcessManager(
    private val resolver: CliBinaryResolver = CliBinaryResolver(),
    private val config: CliProcessConfig = CliProcessConfig(),
) {
    private val logger = getLogger(this)

    private val globalSemaphore = Semaphore(config.maxConcurrentGlobal)
    private val sessionSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val healthCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val backendVersionCache = ConcurrentHashMap<String, Pair<Long, String?>>()
    private val loggedVersions = ConcurrentHashMap.newKeySet<String>()
    private val warnedMismatches = ConcurrentHashMap.newKeySet<String>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.backendHealthTimeoutMs))
        .build()

    /** Resolve a raw request into an explicit spec (throws [CliUnavailableException]). */
    fun resolve(request: CliRunRequest, backendBaseUrl: String? = null): CliRunSpec {
        val binary = resolver.resolve()
        val (shell, shellArgv, commandLine) = buildShellInvocation(binary, request.args)
        val timeoutMs = (request.timeoutSeconds
            ?: config.defaultTimeoutMs / 1000)
            .coerceIn(1, config.maxTimeoutMs / 1000) * 1000
        return CliRunSpec(
            binaryPath = binary,
            shell = shell,
            shellArgv = shellArgv,
            commandLine = commandLine,
            timeoutMs = timeoutMs,
            graceMs = config.graceMs,
            outputBufferBytes = config.outputBufferBytes,
            returnMaxTokens = config.returnMaxTokens,
            workingDir = request.workingDir,
            sessionId = request.sessionId,
            taskId = request.taskId,
            env = buildWhitelistedEnv(backendBaseUrl),
        )
    }

    /**
     * Run one CLI command to completion (or timeout / cancellation).
     *
     * @param cancelToken optional cancellation token for explicit abort; when
     *   cancelled, the process tree is killed and the result carries
     *   [CliResult.aborted] = true. External coroutine cancellation kills the
     *   tree and rethrows (structured concurrency is not swallowed).
     */
    suspend fun run(
        request: CliRunRequest,
        backendBaseUrl: String? = null,
        cancelToken: Job? = null,
    ): CliResult {
        val spec = try {
            resolve(request, backendBaseUrl)
        } catch (e: CliUnavailableException) {
            return CliResult(null, "", "", infraFailure = e.message, durationMs = 0)
        }
        checkVersionAlignment(spec, backendBaseUrl)

        if (backendBaseUrl != null && !config.allowServerAutoStart && !backendHealthy(backendBaseUrl)) {
            return CliResult(
                null, "", "",
                infraFailure = "Backend unreachable at $backendBaseUrl; refusing to let the CLI " +
                    "auto-start a server (allowServerAutoStart=false)",
                durationMs = 0,
            )
        }

        if (!acquireSlots(spec)) {
            return CliResult(null, "", "", rejected = true, durationMs = 0)
        }
        val start = System.currentTimeMillis()
        try {
            return execute(spec, cancelToken, start)
        } finally {
            releaseSlots(spec)
        }
    }

    // -- internal ------------------------------------------------------------

    private suspend fun execute(spec: CliRunSpec, cancelToken: Job?, startMs: Long): CliResult {
        val pb = ProcessBuilder(spec.shellArgv)
        pb.environment().clear()
        pb.environment().putAll(spec.env)
        spec.workingDir?.let { pb.directory(it.toFile()) }
        pb.redirectErrorStream(false)

        val proc = try {
            pb.start()
        } catch (e: IOException) {
            return CliResult(
                null, "", "", infraFailure = "spawn failed: ${e.message}",
                durationMs = System.currentTimeMillis() - startMs,
            )
        }

        val stdout = HeadTailBuffer(spec.outputBufferBytes)
        val stderr = HeadTailBuffer(spec.outputBufferBytes)
        val outThread = startReader(proc.inputStream, stdout)
        val errThread = startReader(proc.errorStream, stderr)

        val outcome: Outcome = try {
            withTimeoutOrNull(spec.timeoutMs) {
                while (proc.isAlive) {
                    if (cancelToken != null && !cancelToken.isActive) {
                        return@withTimeoutOrNull Outcome.Aborted
                    }
                    delay(50)
                }
                Outcome.Exited(proc.exitValue())
            } ?: Outcome.TimedOut
        } catch (e: CancellationException) {
            // External cancellation: kill the tree, then rethrow so structured
            // concurrency sees the cancellation (no result is returned).
            killTree(proc, spec.graceMs)
            outThread.join(2_000)
            errThread.join(2_000)
            throw e
        }

        if (outcome !is Outcome.Exited) {
            killTree(proc, spec.graceMs)
        }
        outThread.join(2_000)
        errThread.join(2_000)
        val durationMs = System.currentTimeMillis() - startMs
        val outText = stdout.text()
        val errText = stderr.text()
        CliMetrics.recordCall(
            durationMs = durationMs,
            timedOut = outcome == Outcome.TimedOut,
            truncated = outText.contains("dropped") || errText.contains("dropped"),
        )

        return when (outcome) {
            is Outcome.Exited -> CliResult(
                outcome.code, outText, errText, durationMs = durationMs
            )
            Outcome.TimedOut -> CliResult(
                null, outText, errText, timedOut = true, durationMs = durationMs
            )
            Outcome.Aborted -> CliResult(
                null, outText, errText, aborted = true, durationMs = durationMs
            )
        }
    }

    /**
     * Version alignment (design §4.2): log the resolved CLI version once, and
     * warn when its major.minor differs from the backend's (API drift risk —
     * prefer a bundle binary that ships with the backend).
     */
    private suspend fun checkVersionAlignment(spec: CliRunSpec, backendBaseUrl: String?) {
        // Version alignment only makes sense for the browser4-cli binary
        // itself — skip the (slow) version probe for test/other binaries.
        if (!spec.binaryPath.fileName.toString().startsWith("browser4-cli")) return
        val cliVersion = resolver.version(spec.binaryPath) ?: return
        if (loggedVersions.add(spec.binaryPath.toString())) {
            logger.info("browser4-cli resolved: {} ({})", spec.binaryPath, cliVersion)
        }
        if (backendBaseUrl == null) return
        val backendVersion = backendVersion(backendBaseUrl) ?: return
        if (cliBackendVersionMismatch(cliVersion, backendVersion)) {
            if (warnedMismatches.add("${spec.binaryPath}|$cliVersion|$backendVersion")) {
                logger.warn(
                    "browser4-cli version mismatch: CLI={} backend={} — prefer a bundle binary matching the backend",
                    cliVersion, backendVersion
                )
            }
        }
    }

    private suspend fun backendVersion(baseUrl: String): String? {
        val now = System.currentTimeMillis()
        backendVersionCache[baseUrl]?.let { (ts, v) -> if (now - ts < 5 * 60_000) return v }
        val version = withContext(Dispatchers.IO) {
            runCatching {
                val uri = URI.create(baseUrl.trimEnd('/') + "/api/system/build")
                val req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .GET().build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) {
                    null
                } else {
                    runCatching {
                        pulsarObjectMapper().readTree(resp.body()).get("version")?.asText()
                    }.getOrNull()
                }
            }.getOrNull()
        }
        backendVersionCache[baseUrl] = now to version
        return version
    }

    private fun cliBackendVersionMismatch(cli: String, backend: String): Boolean {
        fun nums(v: String): List<Int> =
            Regex("\\d+").findAll(v).map { it.value.toInt() }.take(2).toList()
        val a = nums(cli)
        val b = nums(backend)
        return a.size >= 2 && b.size >= 2 && (a[0] != b[0] || a[1] != b[1])
    }

    private fun killTree(process: Process, graceMs: Long) {
        val descendants = try {
            process.toHandle().descendants().toList()
        } catch (e: Exception) {
            emptyList()
        }
        val pids = listOf(process.pid()) + descendants.map { it.pid() }
        if (isWindows) {
            // taskkill /T walks the whole tree; /F forces even trapped children.
            pids.forEach { pid -> runQuiet("taskkill", "/PID", pid.toString(), "/T", "/F") }
        } else {
            pids.forEach { pid -> runQuiet("kill", "-TERM", pid.toString()) }
            Thread.sleep(graceMs)
            pids.forEach { pid -> runQuiet("kill", "-KILL", pid.toString()) }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun runQuiet(vararg argv: String) {
        runCatching {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    private fun startReader(stream: InputStream, buffer: HeadTailBuffer): Thread =
        Thread({
            runCatching {
                stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line -> buffer.append(line + "\n") }
                }
            }
        }, "cli-process-reader").apply {
            isDaemon = true
            start()
        }

    private suspend fun backendHealthy(baseUrl: String): Boolean {
        val now = System.currentTimeMillis()
        healthCache[baseUrl]?.let { (ts, ok) -> if (now - ts < 5_000) return ok }
        val healthy = withContext(Dispatchers.IO) {
            runCatching {
                val uri = URI.create(baseUrl.trimEnd('/') + "/actuator/health")
                val req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(config.backendHealthTimeoutMs))
                    .GET().build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                resp.statusCode() == 200 && resp.body().contains("\"status\":\"UP\"")
            }.getOrDefault(false)
        }
        healthCache[baseUrl] = now to healthy
        return healthy
    }

    private fun acquireSlots(spec: CliRunSpec): Boolean {
        if (!globalSemaphore.tryAcquire(config.queueWaitMs, TimeUnit.MILLISECONDS)) return false
        val sessionSem = spec.sessionId?.let {
            sessionSemaphores.computeIfAbsent(it) { Semaphore(config.maxConcurrentPerSession) }
        }
        if (sessionSem != null && !sessionSem.tryAcquire(config.queueWaitMs, TimeUnit.MILLISECONDS)) {
            globalSemaphore.release()
            return false
        }
        return true
    }

    private fun releaseSlots(spec: CliRunSpec) {
        spec.sessionId?.let { sessionSemaphores[it]?.release() }
        globalSemaphore.release()
    }

    // -- shell invocation (M1 argv decision: shell single-argv, no tokenizer) --

    private fun buildShellInvocation(binary: Path, args: String): Triple<String, List<String>, String> {
        val quotedBinary = quoteForShell(binary.toString())
        val commandLine = "$quotedBinary $args".trimEnd()
        return if (isWindows) {
            val shell = if (pwshAvailable.get()) "pwsh" else "powershell"
            // UTF-8 preamble for Windows PowerShell 5.1 OEM codepage safety.
            val preamble = if (shell == "powershell") "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " else ""
            Triple(
                shell,
                listOf(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", preamble + "& " + commandLine),
                commandLine,
            )
        } else {
            Triple("/bin/sh", listOf("/bin/sh", "-c", commandLine), commandLine)
        }
    }

    private val pwshAvailable: AtomicBoolean by lazy {
        AtomicBoolean(
            runCatching {
                val p = ProcessBuilder("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "\$true")
                    .redirectErrorStream(true).start()
                p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        )
    }

    private fun quoteForShell(path: String): String = "'" + path.replace("'", "''") + "'"

    private fun buildWhitelistedEnv(backendBaseUrl: String?): Map<String, String> {
        val base = System.getenv()
        // Case-insensitive lookup (Windows env keys preserve original casing).
        fun pick(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            base.entries.firstOrNull { it.key.equals(n, ignoreCase = isWindows) }?.value
        }
        val env = mutableMapOf<String, String>()
        pick("PATH")?.let { env["PATH"] = it }
        // Windows internals (font/DirectWrite caches etc.) resolve %SystemDrive%
        // literally when the variable is missing and write under the CWD —
        // empirically observed as a literal `%SystemDrive%` dir. Keep it.
        pick("SystemDrive")?.let { env["SystemDrive"] = it }
        pick("SystemRoot", "WINDIR")?.let { env["SystemRoot"] = it }
        pick("ComSpec")?.let { env["ComSpec"] = it }
        pick("PROCESSOR_ARCHITECTURE")?.let { env["PROCESSOR_ARCHITECTURE"] = it }
        pick("TMP", "TEMP")?.let { env["TMP"] = it }
        pick("TEMP", "TMP")?.let { env["TEMP"] = it }
        pick("HOME", "USERPROFILE")?.let { env["HOME"] = it }
        pick("USERPROFILE")?.let { env["USERPROFILE"] = it }
        pick("HOMEDRIVE")?.let { env["HOMEDRIVE"] = it }
        pick("HOMEPATH")?.let { env["HOMEPATH"] = it }
        pick("PATHEXT")?.let { env["PATHEXT"] = it }
        // Deterministic neutral overrides.
        env["NO_COLOR"] = "1"
        env["TERM"] = "dumb"
        env["PAGER"] = "cat"
        env["GIT_PAGER"] = "cat"
        // M0: force the CLI onto the same backend and disable plugin warm restart.
        env["BROWSER4_CLI_DISABLE_PLUGIN_WARM_RESTART"] = "1"
        backendBaseUrl?.let { env["BROWSER4_CLI_SERVER"] = it }
        return env
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private sealed interface Outcome {
        data class Exited(val code: Int) : Outcome
        data object TimedOut : Outcome
        data object Aborted : Outcome
    }

    /**
     * Bounded head+tail output collector (chars). Keeps the first half of the
     * budget as head and the last half as tail; the middle is dropped once the
     * budget is exceeded.
     */
    private class HeadTailBuffer(private val maxChars: Int) {
        private val headCap = maxChars / 2
        private val tailCap = maxChars - headCap
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private var total = 0L

        @Synchronized
        fun append(chunk: String) {
            if (chunk.isEmpty()) return
            total += chunk.length
            if (head.length < headCap) {
                val need = headCap - head.length
                if (chunk.length <= need) {
                    head.append(chunk)
                    return
                }
                head.append(chunk, 0, need)
                tail.append(chunk, need, chunk.length)
            } else {
                tail.append(chunk)
            }
            if (tail.length > tailCap) tail.delete(0, tail.length - tailCap)
        }

        @Synchronized
        fun text(): String {
            val body = head.toString() + tail
            val dropped = total - head.length - tail.length
            return if (dropped > 0) body + "\n…[dropped $dropped chars]…" else body
        }
    }
}
