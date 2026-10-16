package ai.platon.pulsar.chrome.window

import ai.platon.pulsar.chrome.session.ProcessFacts
import ai.platon.pulsar.chrome.session.ProcessTable
import ai.platon.pulsar.common.browser.BrowserFiles
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The probe's non-CDP half: it must read the *session's own* launch, and turn
 * anything it cannot read into UNKNOWN rather than into a warning.
 *
 * The CDP window half (`Browser.getWindowForTarget` on a live browser) cannot be
 * exercised here — it needs a running Chrome — so what is asserted is the
 * session-scoped process evidence and the resulting verdict.
 */
@DisplayName("Session window probe")
class SessionWindowProbeTest {

    private class FakeProcessTable(private val facts: MutableMap<Long, ProcessFacts>) : ProcessTable {
        override fun facts(pid: Long): ProcessFacts? = facts[pid]
        override fun terminateTree(pid: Long): Int = 0
    }

    private fun contextDirWithPid(root: Path, pid: Long): Path {
        val contextDir = Files.createDirectories(root.resolve("cx.001"))
        Files.writeString(contextDir.resolve(BrowserFiles.PID_FILE_NAME), pid.toString())
        return contextDir
    }

    @Test
    @DisplayName("a headed request whose process carries --headless is a verified HIDDEN")
    fun headedRequestLaunchedHeadlessIsHidden(@TempDir root: Path) = runBlocking {
        val contextDir = contextDirWithPid(root, 43512)
        val table = FakeProcessTable(
            mutableMapOf(
                43512L to ProcessFacts(
                    pid = 43512,
                    alive = true,
                    commandLine = "chrome.exe --headless=new --remote-debugging-port=1958 " +
                        "--user-data-dir=${contextDir.resolve("PULSAR_CHROME")}",
                )
            )
        )

        val report = SessionWindowProbe(table).probe(
            driver = null,
            contextDir = contextDir,
            debugPort = 1958,
            requestedDisplayMode = "GUI",
        )

        assertEquals(WindowVisibility.HIDDEN, report.visibility)
        assertEquals(WindowEvidenceSource.PROCESS_COMMAND_LINE, report.source)
        assertEquals(43512L, report.observation.browserPid)
        assertEquals(true, report.observation.launchedHeadless)
    }

    @Test
    @DisplayName("a headed process with no readable window state is UNKNOWN, never a warning")
    fun headedProcessWithoutWindowFactsIsUnknown(@TempDir root: Path) = runBlocking {
        val contextDir = contextDirWithPid(root, 44844)
        val table = FakeProcessTable(
            mutableMapOf(
                44844L to ProcessFacts(
                    pid = 44844,
                    alive = true,
                    commandLine = "chrome.exe --remote-debugging-port=28657 " +
                        "--user-data-dir=${contextDir.resolve("PULSAR_CHROME")}",
                )
            )
        )

        val report = SessionWindowProbe(table).probe(
            driver = null,
            contextDir = contextDir,
            debugPort = 28657,
            requestedDisplayMode = "GUI",
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertFalse(report.verified)
        assertEquals(false, report.observation.launchedHeadless)
    }

    @Test
    @DisplayName("no pid marker yields UNKNOWN and no pid, not a fabricated observation")
    fun missingPidMarkerYieldsUnknown(@TempDir root: Path) = runBlocking {
        val contextDir = Files.createDirectories(root.resolve("cx.002"))

        val report = SessionWindowProbe(FakeProcessTable(mutableMapOf())).probe(
            driver = null,
            contextDir = contextDir,
            debugPort = 1234,
            requestedDisplayMode = "GUI",
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertEquals(WindowEvidenceSource.NONE, report.source)
        assertNull(report.observation.browserPid)
        assertNull(report.observation.launchedHeadless)
        assertTrue(report.reason.isNotBlank(), report.reason)
    }

    @Test
    @DisplayName("a pid marker whose process is gone is UNKNOWN: the browser already exited")
    fun deadProcessIsUnknown(@TempDir root: Path) = runBlocking {
        val contextDir = contextDirWithPid(root, 4242)
        val table = FakeProcessTable(mutableMapOf(4242L to ProcessFacts(4242, alive = false)))

        val report = SessionWindowProbe(table).probe(
            driver = null,
            contextDir = contextDir,
            debugPort = 1234,
            requestedDisplayMode = "GUI",
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertEquals(false, report.observation.processAlive)
    }
}
