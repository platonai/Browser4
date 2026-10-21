package ai.platon.pulsar.chrome.session

import ai.platon.pulsar.common.browser.BrowserFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shutdown path must terminate the browser tree a session launched, and must
 * never claim it did when it could not verify it.
 */
@DisplayName("Browser process reaper")
class BrowserProcessReaperTest {

    /** Records what was asked of it; returns scripted facts. */
    private class FakeProcessTable(
        val facts: MutableMap<Long, ProcessFacts> = mutableMapOf(),
    ) : ProcessTable {
        val terminated = mutableListOf<Long>()
        /** When true the tree stays alive however often it is killed. */
        var survivesKill: Boolean = false

        fun alive(pid: Long, commandLine: String?, command: String? = "chrome.exe") {
            facts[pid] = ProcessFacts(pid, alive = true, command = command, commandLine = commandLine)
        }

        override fun facts(pid: Long): ProcessFacts? = facts[pid]

        override fun terminateTree(pid: Long): Int {
            terminated += pid
            if (!survivesKill) {
                facts[pid] = ProcessFacts(pid, alive = false)
            }
            return 1
        }
    }

    private fun contextDirWithPid(root: Path, pid: Long): Path {
        val contextDir = root.resolve("cx.001")
        Files.createDirectories(contextDir)
        Files.writeString(contextDir.resolve(BrowserFiles.PID_FILE_NAME), pid.toString())
        return contextDir
    }

    @Test
    @DisplayName("a context dir without a pid marker is reported as unknown, not as terminated")
    fun missingPidMarkerIsUnknown(@TempDir root: Path) {
        val contextDir = Files.createDirectories(root.resolve("cx.001"))
        val report = BrowserProcessReaper(FakeProcessTable(), killWaitMillis = 10).terminate(contextDir)

        assertEquals(ProcessTermination.NO_PID_FILE, report.outcome)
        assertFalse(report.isGone, "nothing was proven, so nothing may be claimed")
        assertTrue(report.summary.contains("unknown"), report.summary)
    }

    @Test
    @DisplayName("a pid that is already gone counts as gone")
    fun deadPidIsGone(@TempDir root: Path) {
        val table = FakeProcessTable()
        val contextDir = contextDirWithPid(root, 4242)
        table.facts[4242] = ProcessFacts(4242, alive = false)

        val report = BrowserProcessReaper(table, killWaitMillis = 10).terminate(contextDir)

        assertEquals(ProcessTermination.ALREADY_GONE, report.outcome)
        assertTrue(report.isGone)
        assertTrue(table.terminated.isEmpty(), "a dead pid must not be killed again")
    }

    @Test
    @DisplayName("a live pid whose command line is not this profile is left alone")
    fun foreignProcessIsNeverKilled(@TempDir root: Path) {
        val table = FakeProcessTable()
        val contextDir = contextDirWithPid(root, 4243)
        // PID reuse: the number now belongs to something else entirely.
        table.alive(4243, "\"C:\\Windows\\System32\\notepad.exe\" C:\\notes.txt", command = "notepad.exe")

        val report = BrowserProcessReaper(table, killWaitMillis = 10).terminate(contextDir)

        assertEquals(ProcessTermination.NOT_OURS, report.outcome)
        assertFalse(report.isGone)
        assertTrue(table.terminated.isEmpty(), "an unverified process is never killed")
        assertTrue(report.summary.contains("left alone"), report.summary)
    }

    @Test
    @DisplayName("a live pid whose command line names this profile is killed and verified gone")
    fun verifiedBrowserIsTerminated(@TempDir root: Path) {
        val table = FakeProcessTable()
        val contextDir = contextDirWithPid(root, 4244)
        table.alive(
            4244,
            "chrome.exe --headless=new --remote-debugging-port=0 " +
                "--user-data-dir=\"${contextDir.resolve("PULSAR_CHROME")}\"",
        )

        val report = BrowserProcessReaper(table, killWaitMillis = 500).terminate(contextDir)

        assertEquals(ProcessTermination.TERMINATED, report.outcome)
        assertTrue(report.isGone)
        assertEquals(listOf(4244L), table.terminated)
        assertEquals(1, report.requestedTerminations)
    }

    @Test
    @DisplayName("a process that survives the kill is reported as survived, never as terminated")
    fun survivingProcessIsReported(@TempDir root: Path) {
        val table = FakeProcessTable()
        val contextDir = contextDirWithPid(root, 4245)
        table.alive(4245, "chrome.exe --user-data-dir=${contextDir.resolve("PULSAR_CHROME")}")
        table.survivesKill = true

        val report = BrowserProcessReaper(table, killWaitMillis = 60, pollIntervalMillis = 10)
            .terminate(contextDir)

        assertEquals(ProcessTermination.SURVIVED, report.outcome)
        assertFalse(report.isGone)
        assertTrue(report.summary.contains("still running"), report.summary)
    }

    @Test
    @DisplayName("the command line match tolerates separators, case and a shortened path")
    fun commandLineMatchIsTolerant() {
        val contextDir = Path.of("C:\\Users\\x\\AppData\\Local\\Temp\\b4\\context\\groups\\default\\PULSAR_CHROME\\cx.007")

        assertTrue(
            BrowserProcessReaper.commandsThisProfile(
                ProcessFacts(1, true, commandLine = "chrome.exe --user-data-dir=c:/users/X/appdata/local/temp/B4/context/groups/default/PULSAR_CHROME/cx.007/PULSAR_CHROME"),
                contextDir,
            )
        )
        // 8.3 / relative forms still name the rotation dir and the profile dir.
        assertTrue(
            BrowserProcessReaper.commandsThisProfile(
                ProcessFacts(1, true, commandLine = "chrome.exe --user-data-dir=cx.007\\PULSAR_CHROME"),
                contextDir,
            )
        )
        // No --user-data-dir at all: not our browser.
        assertFalse(
            BrowserProcessReaper.commandsThisProfile(
                ProcessFacts(1, true, commandLine = "chrome.exe --remote-debugging-port=9222"),
                contextDir,
            )
        )
        // An unreadable command line is never a match.
        assertFalse(BrowserProcessReaper.commandsThisProfile(ProcessFacts(1, true), contextDir))
        assertFalse(
            BrowserProcessReaper.commandsThisProfile(
                ProcessFacts(1, true, commandLine = "chrome.exe --user-data-dir=cx.008\\PULSAR_CHROME"),
                contextDir,
            ),
            "a different rotation dir is a different browser",
        )
    }
}
