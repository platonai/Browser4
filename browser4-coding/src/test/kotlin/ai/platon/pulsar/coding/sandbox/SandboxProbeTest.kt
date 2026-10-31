package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan P3-2 — the two-write self-check.
 *
 * The runner is injectable so the contract can be pinned without a real sandbox account: only the
 * observed writes decide the outcome, and any mismatch is a failure string (the caller turns that
 * into `SandboxUnavailableException`).
 */
@DisplayName("sandbox write probe")
class SandboxProbeTest {

    @TempDir
    lateinit var tempDir: Path

    private val account = SandboxAccount("b4-agent", charArrayOf('s'))

    private fun targetOf(spec: ProcessSpec): Path = Path.of(spec.command.last())

    private fun probeFiles(dir: Path): List<String> =
        Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.startsWith(".b4-sandbox-probe-") }
                .toList()
        }

    @Test
    @DisplayName("inside succeeds and outside is denied: the probe passes")
    fun passesWhenInsideWritesAndOutsideIsDenied() {
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))

        val failure = SandboxProbe.verify(ws, account, outside) { spec, _ ->
            val target = targetOf(spec)
            if (target.startsWith(ws)) {
                Files.writeString(target, "probe")
                SandboxProbe.Outcome(0, "")
            } else {
                SandboxProbe.Outcome(1, "Access is denied.")
            }
        }

        assertNull(failure, "both halves behaved as required")
        assertTrue(probeFiles(ws).isEmpty(), "the inside probe file is cleaned up")
        assertTrue(probeFiles(outside).isEmpty(), "the outside probe file is cleaned up")
    }

    @Test
    @DisplayName("a refused inside write fails the probe")
    fun insideFailureFails() {
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))

        val failure = SandboxProbe.verify(ws, account, outside) { _, _ ->
            SandboxProbe.Outcome(1, "Access is denied.")
        }

        assertTrue(failure != null)
        assertTrue(failure!!.contains("cannot write inside the workspace"), failure)
    }

    @Test
    @DisplayName("a successful outside write fails the probe: the ACL matrix is not enforced")
    fun outsideSuccessFails() {
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))

        val failure = SandboxProbe.verify(ws, account, outside) { spec, _ ->
            Files.writeString(targetOf(spec), "probe")
            SandboxProbe.Outcome(0, "")
        }

        assertTrue(failure != null)
        assertTrue(failure!!.contains("wrote outside its granted roots"), failure)
        assertTrue(probeFiles(outside).isEmpty(), "the leaked file is removed")
    }

    @Test
    @DisplayName("an exit code of 0 without the file is still a failure")
    fun missingInsideFileFails() {
        val ws = Files.createDirectories(tempDir.resolve("ws"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))

        val failure = SandboxProbe.verify(ws, account, outside) { _, _ ->
            SandboxProbe.Outcome(0, "")
        }

        assertTrue(failure != null)
        assertTrue(failure!!.contains("cannot write inside"), failure)
    }

    @Test
    @DisplayName("the outside target is a directory outside the workspace")
    fun outsideTargetIsOutsideTheWorkspace() {
        val ws = Files.createDirectories(tempDir.resolve("ws"))

        val outside = SandboxProbe.outsideTarget(ws)

        assertTrue(outside != null, "the operator profile or the workspace parent exists")
        assertFalse(outside!!.startsWith(ws), "the probe must never target the writable workspace: $outside")
    }

    @Test
    @DisplayName("a probe that keeps stdout open is force-destroyed at the timeout instead of blocking on EOF")
    fun launchTimesOutAndForciblyDestroys() {
        val process = StalledProcess()

        val outcome = SandboxProbe.launch(
            ProcessSpec(listOf("cmd.exe", "/c", "echo", "probe"), tempDir, emptyMap()),
            account,
            start = { process },
            timeoutSeconds = 1,
            drainTimeoutSeconds = 1,
        )

        assertEquals(-1, outcome.exitCode)
        assertTrue(outcome.output.contains("timed out"), outcome.output)
        assertTrue(process.destroyed, "the stalled probe is killed, never awaited forever")
    }

    /** A process that never exits and keeps its stdout open until it is destroyed. */
    private class StalledProcess : Process() {
        private val eof = CountDownLatch(1)

        @Volatile
        var destroyed: Boolean = false
            private set

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                eof.await()
                return -1
            }
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = throw IllegalThreadStateException("still running")

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false

        override fun exitValue(): Int = throw IllegalThreadStateException("still running")

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            eof.countDown()
            return this
        }

        override fun isAlive(): Boolean = !destroyed

        override fun pid(): Long = 4242
    }
}
