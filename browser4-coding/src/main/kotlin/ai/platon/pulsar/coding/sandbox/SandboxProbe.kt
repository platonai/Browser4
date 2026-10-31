package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Plan P3-2 — the two-write self-check.
 *
 * A sandbox that cannot write inside its own workspace breaks every build, and one that can write
 * outside it is worse than `off` because it claims a boundary it does not enforce. Both halves are
 * therefore verified once per workspace right after provisioning: the inside write must succeed and
 * the outside write must be refused by the OS. Any mismatch is a hard failure — the caller turns
 * this into [SandboxUnavailableException], never into a warning or a fallback.
 */
internal object SandboxProbe {

    private val logger = LoggerFactory.getLogger(SandboxProbe::class.java)

    private const val TIMEOUT_SECONDS = 30L

    /** Bounded wait for the reader thread after the child exits or is killed. */
    private const val DRAIN_TIMEOUT_SECONDS = 5L

    /** What one probe command produced. */
    data class Outcome(val exitCode: Int, val output: String)

    /**
     * @param workspace the directory the account must be able to write
     * @param outside a directory the account must not be able to write (normally the operator profile)
     * @param runner injectable for tests; the default launches through the token sandbox
     * @return `null` when both writes behave as required, otherwise the failure description
     */
    fun verify(
        workspace: Path,
        account: SandboxAccount,
        outside: Path,
        runner: (ProcessSpec, SandboxAccount) -> Outcome = { spec, account -> launch(spec, account) },
    ): String? {
        val token = UUID.randomUUID().toString().take(8)
        val inside = workspace.resolve(".b4-sandbox-probe-$token.txt")
        val outsideFile = outside.resolve(".b4-sandbox-probe-$token.txt")
        try {
            val insideOutcome = runner(writeSpec(workspace, inside), account)
            if (insideOutcome.exitCode != 0 || !Files.exists(inside)) {
                return "the sandbox account cannot write inside the workspace '$workspace' " +
                    "(exit=${insideOutcome.exitCode}, output=${insideOutcome.output.trim().take(200)})"
            }

            val outsideOutcome = runner(writeSpec(workspace, outsideFile), account)
            if (outsideOutcome.exitCode == 0 || Files.exists(outsideFile)) {
                return "the sandbox account wrote outside its granted roots ('$outsideFile'); " +
                    "the ACL matrix is not enforced (exit=${outsideOutcome.exitCode})"
            }

            logger.info("sandbox write probe passed: workspace={} outside={}", workspace, outside)
            return null
        } finally {
            runCatching { Files.deleteIfExists(inside) }
            runCatching { Files.deleteIfExists(outsideFile) }
        }
    }

    /**
     * The directory the outside write targets: the operator profile (the deny root) when it exists,
     * otherwise the workspace's parent. `null` means no directory outside the workspace exists —
     * the caller must treat that as a probe failure rather than skip the check.
     */
    fun outsideTarget(workspace: Path): Path? {
        defaultDenyRoots().firstOrNull { Files.isDirectory(it) }?.let { return it }
        val parent = workspace.toAbsolutePath().normalize().parent
        return parent?.takeIf { Files.isDirectory(it) }
    }

    private fun writeSpec(directory: Path, target: Path): ProcessSpec = ProcessSpec(
        command = listOf("cmd.exe", "/c", "echo", "probe", ">", target.toAbsolutePath().toString()),
        directory = directory,
        environment = emptyMap(),
    )

    /**
     * Runs the probe with both halves under the timeout: the output is drained on a daemon thread
     * while the caller waits for the child.
     *
     * Draining on the calling thread first (the historical shape) blocks on EOF, and a child or
     * grandchild that keeps stdout open would make that wait permanent — the timeout and the
     * `destroyForcibly` were unreachable. Reading concurrently keeps the "drain or the pipe fills"
     * intent, while the wait, the kill and the post-kill drain all stay bounded.
     */
    internal fun launch(
        spec: ProcessSpec,
        account: SandboxAccount,
        start: (ProcessSpec) -> Process = { processSpec ->
            ProcessLauncher.start(processSpec.copy(sandboxAccount = account), redirectErrorStream = true)
        },
        timeoutSeconds: Long = TIMEOUT_SECONDS,
        drainTimeoutSeconds: Long = DRAIN_TIMEOUT_SECONDS,
    ): Outcome {
        val process = start(spec)
        val output = AtomicReference("")
        val drained = CountDownLatch(1)
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().use { output.set(it.readText()) }
            } catch (e: Exception) {
                logger.debug("sandbox probe output reader stopped: {}", e.message)
            } finally {
                drained.countDown()
            }
        }, "sandbox-probe-reader")
        reader.isDaemon = true
        reader.start()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            // A grandchild can still hold the pipe open, so the drain gets a short window only.
            drained.await(drainTimeoutSeconds, TimeUnit.SECONDS)
            return Outcome(-1, "probe timed out after ${timeoutSeconds}s")
        }
        // The child exited; wait for EOF, but bounded in case a lingering descendant holds it.
        drained.await(drainTimeoutSeconds, TimeUnit.SECONDS)
        return Outcome(process.exitValue(), output.get())
    }
}
