package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The sandbox's process-tree lifetime.
 *
 * `KILL_ON_JOB_CLOSE` is what makes "the backend is gone" also mean "its children are gone". The
 * 2026-11-16 review found stray `python`/`cmd` children from earlier runs, and a task-started daemon
 * could outlive its task — the job is the OS mechanism that ends both.
 */
@DisplayName("windows sandbox job")
class WindowsJobTest {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private fun startLongRunningChild(): Process =
        ProcessBuilder("cmd.exe", "/c", "ping -n 60 127.0.0.1 > nul").redirectErrorStream(true).start()

    private fun awaitDeath(process: Process, timeoutSeconds: Long = 10): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return true
            Thread.sleep(50)
        }
        return !process.isAlive
    }

    @Test
    @DisplayName("closing the job kills the processes attached to it")
    fun closingTheJobKillsItsMembers() {
        assumeTrue(isWindows, "job objects only exist on Windows")
        val job = WindowsJob("b4-test-job")
        val child = startLongRunningChild()
        try {
            assertTrue(job.isOpen, "the job must be usable on this machine")
            assertTrue(job.attach(child.pid()), "the child must join the job")
            assertTrue(child.isAlive, "the child is still running before the close")

            job.close()

            assertTrue(awaitDeath(child), "closing the job must terminate its members")
        } finally {
            job.close()
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("closing twice is a no-op, and a closed job accepts no new members")
    fun closeIsIdempotent() {
        assumeTrue(isWindows, "job objects only exist on Windows")
        val job = WindowsJob("b4-test-idempotent")
        val child = startLongRunningChild()
        try {
            job.close()
            job.close()
            assertFalse(job.isOpen, "a closed job must report itself closed")
            assertFalse(job.attach(child.pid()), "a closed job cannot take new members")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    @DisplayName("attaching a process that is already gone fails instead of throwing")
    fun attachingADeadProcessFails() {
        assumeTrue(isWindows, "job objects only exist on Windows")
        val job = WindowsJob("b4-test-dead")
        val finished = ProcessBuilder("cmd.exe", "/c", "exit 0").start()
        finished.waitFor(10, TimeUnit.SECONDS)
        try {
            assertFalse(job.attach(finished.pid()), "a finished pid cannot join the job")
        } finally {
            job.close()
        }
    }
}
