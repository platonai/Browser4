package ai.platon.pulsar.common

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test process cleanup improvements
 */
class ProcessCleanupTest {

    @Test
    @DisplayName("test isProcessAlive with invalid PID")
    fun testIsProcessAliveWithInvalidPid() {
        // Negative PID should always be false
        assertFalse(Runtimes.isProcessAlive(-1))
        assertFalse(Runtimes.isProcessAlive(0))

        // Use Int.MAX_VALUE which is guaranteed not to be a valid PID
        assertFalse(Runtimes.isProcessAlive(Int.MAX_VALUE))
    }

    @Test
    @DisplayName("test isProcessAlive with Int overload")
    fun testIsProcessAliveWithIntOverload() {
        // Test the Int overload we added
        val invalidPid: Int = -1
        assertFalse(Runtimes.isProcessAlive(invalidPid))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Process creation test may behave differently on Windows")
    @DisplayName("test process cleanup with short-lived process")
    fun testProcessCleanupWithShortLivedProcess() {
        // Create a short-lived process
        val processBuilder = ProcessBuilder("sh", "-c", "sleep 1")
        val process = processBuilder.start()
        val pid = process.pid()

        // Process should be alive
        assertTrue(process.isAlive)
        assertTrue(Runtimes.isProcessAlive(pid.toInt()))

        // Wait for process to complete
        process.waitFor()

        // Process should be dead
        assertFalse(process.isAlive)
        assertFalse(Runtimes.isProcessAlive(pid.toInt()))
    }

    @Test
    @DisplayName("destroyProcess uses full duration precision for wait timeout")
    fun destroyProcessUsesMillisecondPrecision() {
        val events = mutableListOf<String>()
        val process = FakeProcess(
            pid = 100,
            events = events,
            waitResults = ArrayDeque(listOf(true))
        )

        Runtimes.destroyProcess(process, Duration.ofMillis(500))

        assertEquals(1, process.waitCalls.size)
        assertEquals(500L, process.waitCalls.single().timeout)
        assertEquals(TimeUnit.MILLISECONDS, process.waitCalls.single().unit)
        assertEquals(listOf("parent.destroy"), events)
    }

    @Test
    @DisplayName("destroyProcess keeps children untouched when parent exits gracefully")
    fun destroyProcessDoesNotKillChildrenWhenParentStopsGracefully() {
        val events = mutableListOf<String>()
        val child = FakeProcessHandle(101, "child", events)
        val process = FakeProcess(
            pid = 100,
            events = events,
            children = listOf(child),
            waitResults = ArrayDeque(listOf(true))
        )

        Runtimes.destroyProcess(process, Duration.ofSeconds(1))

        assertEquals(listOf("parent.destroy"), events)
        assertFalse(child.destroyInvoked)
        assertFalse(child.destroyForciblyInvoked)
    }

    @Test
    @DisplayName("destroyProcess escalates by terminating children before force killing parent")
    fun destroyProcessKillsChildrenBeforeForcingParent() {
        val events = mutableListOf<String>()
        val child = FakeProcessHandle(101, "child", events)
        val process = FakeProcess(
            pid = 100,
            events = events,
            children = listOf(child),
            destroyStopsProcess = false,
            waitResults = ArrayDeque(listOf(false, true))
        )

        Runtimes.destroyProcess(process, Duration.ofSeconds(1))

        assertTrue(events.indexOf("parent.destroy") >= 0)
        assertTrue(events.indexOf("child.destroy") > events.indexOf("parent.destroy"))
        assertTrue(events.indexOf("parent.destroyForcibly") > events.indexOf("child.destroy"))
        assertTrue(child.destroyInvoked)
        assertTrue(child.destroyForciblyInvoked)
    }

    @Test
    @DisplayName("destroyProcessForcibly by name ignores blank pattern")
    fun destroyProcessForciblyByNameIgnoresBlankPattern() {
        assertEquals(null, Runtimes.buildKillProcessesByNamePatternCommand("   ", isWindows = true, isPosix = false))
    }

    @Test
    @DisplayName("destroyProcessForcibly by name builds Windows command as separate arguments")
    fun destroyProcessForciblyByNameBuildsWindowsCommandSafely() {
        assertEquals(
            listOf("taskkill", "/F", "/T", "/IM", "chrome.exe"),
            Runtimes.buildKillProcessesByNamePatternCommand("chrome.exe", isWindows = true, isPosix = false)
        )
    }

    @Test
    @DisplayName("destroyProcessForcibly by name builds POSIX command as separate arguments")
    fun destroyProcessForciblyByNameBuildsPosixCommandSafely() {
        val pattern = "chrome --user-data-dir=/tmp/test profile"
        assertEquals(
            listOf("pkill", "-f", "--", pattern),
            Runtimes.buildKillProcessesByNamePatternCommand(pattern, isWindows = false, isPosix = true)
        )
    }

    @Test
    @DisplayName("destroyProcessForcibly by name returns null for unsupported OS")
    fun destroyProcessForciblyByNameReturnsNullForUnsupportedOs() {
        assertEquals(null, Runtimes.buildKillProcessesByNamePatternCommand("chrome", isWindows = false, isPosix = false))
    }

    private data class WaitCall(val timeout: Long, val unit: TimeUnit)

    private class FakeProcess(
        private val pid: Long,
        private val events: MutableList<String>,
        children: List<FakeProcessHandle> = emptyList(),
        private val destroyStopsProcess: Boolean = true,
        private val waitResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true))
    ) : Process() {
        private var alive = true
        private val handle = FakeProcessHandle(pid, "parent", events, children)
        val waitCalls = mutableListOf<WaitCall>()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls += WaitCall(timeout, unit)
            val result = waitResults.removeFirstOrNull() ?: !alive
            if (result) {
                alive = false
            }
            return result
        }

        override fun exitValue(): Int {
            if (alive) {
                throw IllegalThreadStateException("Process is still alive")
            }
            return 0
        }

        override fun destroy() {
            events += "parent.destroy"
            if (destroyStopsProcess) {
                alive = false
            }
        }

        override fun destroyForcibly(): Process {
            events += "parent.destroyForcibly"
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pid

        override fun toHandle(): ProcessHandle = handle
    }

    private class FakeProcessHandle(
        private val pid: Long,
        private val name: String,
        private val events: MutableList<String>,
        private val children: List<FakeProcessHandle> = emptyList(),
        private var alive: Boolean = true,
    ) : ProcessHandle {
        var destroyInvoked = false
            private set
        var destroyForciblyInvoked = false
            private set

        override fun pid(): Long = pid

        override fun info(): ProcessHandle.Info = object : ProcessHandle.Info {
            override fun command(): Optional<String> = Optional.of(name)

            override fun commandLine(): Optional<String> = Optional.of(name)

            override fun arguments(): Optional<Array<String>> = Optional.empty()

            override fun startInstant(): Optional<Instant> = Optional.empty()

            override fun totalCpuDuration(): Optional<Duration> = Optional.empty()

            override fun user(): Optional<String> = Optional.empty()
        }

        override fun parent(): Optional<ProcessHandle> = Optional.empty()

        override fun children(): Stream<ProcessHandle> = children.stream().map { it as ProcessHandle }

        override fun descendants(): Stream<ProcessHandle> = children.stream().flatMap { child ->
            Stream.concat(Stream.of(child as ProcessHandle), child.descendants())
        }

        override fun destroy(): Boolean {
            destroyInvoked = true
            events += "$name.destroy"
            return true
        }

        override fun destroyForcibly(): Boolean {
            destroyForciblyInvoked = true
            alive = false
            events += "$name.destroyForcibly"
            return true
        }

        override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

        override fun supportsNormalTermination(): Boolean = true

        override fun isAlive(): Boolean = alive

        override fun compareTo(other: ProcessHandle): Int = pid.compareTo(other.pid())
    }
}
