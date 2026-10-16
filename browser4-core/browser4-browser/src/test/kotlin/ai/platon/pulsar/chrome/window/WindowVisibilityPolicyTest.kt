package ai.platon.pulsar.chrome.window

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The headed-window check must never warn about a window it can see, and must
 * never claim success when it could not check at all. Both halves of that are
 * decided here, from facts, so they can be asserted without a browser.
 */
@DisplayName("Window visibility policy")
class WindowVisibilityPolicyTest {

    @Test
    @DisplayName("a headless process for a session that asked for a window is a verified HIDDEN")
    fun headedSessionLaunchedHeadlessIsHidden() {
        // The regression the check exists for: `open --headed` produced a headless
        // browser. The verdict is a fact about THIS session's process, which is
        // what the machine-wide process scan could never see.
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(
                requestedDisplayMode = "GUI",
                launchedHeadless = true,
                processAlive = true,
                browserPid = 43512,
                debugPort = 1958,
            )
        )

        assertEquals(WindowVisibility.HIDDEN, report.visibility)
        assertEquals(WindowEvidenceSource.PROCESS_COMMAND_LINE, report.source)
        assertTrue(report.verified)
        assertTrue(report.reason.contains("--headless"), report.reason)
        assertTrue(report.reason.contains("GUI"), report.reason)
    }

    @Test
    @DisplayName("a healthy headed session is VISIBLE, so no warning can fire")
    fun healthyHeadedSessionIsVisible() {
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(
                requestedDisplayMode = "GUI",
                launchedHeadless = false,
                processAlive = true,
                browserPid = 43512,
                debugPort = 1958,
                windowState = "normal",
                windowId = 1,
                bounds = WindowBounds(0, 0, 1280, 900),
                pageTargetUrls = listOf("https://example.com/"),
            )
        )

        assertEquals(WindowVisibility.VISIBLE, report.visibility)
        assertEquals(WindowEvidenceSource.CDP_WINDOW, report.source)
        assertTrue(report.verified)
        assertTrue(report.reason.contains("windowState=normal"), report.reason)
    }

    @Test
    @DisplayName("a maximized or fullscreen window is VISIBLE too")
    fun maximizedWindowIsVisible() {
        listOf("maximized", "fullscreen").forEach { state ->
            val report = WindowVisibilityPolicy.classify(
                WindowObservation(launchedHeadless = false, processAlive = true, windowState = state)
            )
            assertEquals(WindowVisibility.VISIBLE, report.visibility, "state=$state")
        }
    }

    @Test
    @DisplayName("a minimized window is a verified HIDDEN")
    fun minimizedWindowIsHidden() {
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(
                requestedDisplayMode = "GUI",
                launchedHeadless = false,
                processAlive = true,
                windowState = "minimized",
            )
        )

        assertEquals(WindowVisibility.HIDDEN, report.visibility)
        assertEquals(WindowEvidenceSource.CDP_WINDOW, report.source)
        assertTrue(report.reason.contains("minimized"), report.reason)
    }

    @Test
    @DisplayName("a session that asked for headless is HIDDEN, and its own reason says so")
    fun headlessSessionIsHidden() {
        val requested = WindowVisibilityPolicy.classify(
            WindowObservation(requestedDisplayMode = "HEADLESS", launchedHeadless = true, processAlive = true)
        )
        assertEquals(WindowVisibility.HIDDEN, requested.visibility)
        assertTrue(requested.reason.contains("headless"), requested.reason)

        // Even when the live process could not be read, the session's own request
        // is enough to say there is no window — and it is not the headed case.
        val unreadable = WindowVisibilityPolicy.classify(
            WindowObservation(requestedDisplayMode = "HEADLESS", launchedHeadless = null)
        )
        assertEquals(WindowVisibility.HIDDEN, unreadable.visibility)
        assertEquals(WindowEvidenceSource.DISPLAY_MODE, unreadable.source)
    }

    @Test
    @DisplayName("a headed process whose window state could not be read is UNKNOWN, not a warning")
    fun unreadableWindowStateIsUnknown() {
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(
                requestedDisplayMode = "GUI",
                launchedHeadless = false,
                processAlive = true,
                browserPid = 44844,
                debugPort = 28657,
                windowState = null,
            )
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertFalse(report.verified, "an unchecked window must not be reported as verified")
        assertTrue(report.reason.contains("could not be read"), report.reason)
    }

    @Test
    @DisplayName("a window state without a readable launch mode is UNKNOWN: headless cannot be ruled out")
    fun windowStateAloneIsNotEnough() {
        // Headless Chrome also answers getWindowForTarget with a synthetic window,
        // so a window state alone does not prove a visible window exists.
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(launchedHeadless = null, processAlive = true, windowState = "normal")
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertTrue(report.reason.contains("headless"), report.reason)
    }

    @Test
    @DisplayName("no pid marker at all is UNKNOWN and says what was missing")
    fun missingPidMarkerIsUnknown() {
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(requestedDisplayMode = "GUI", launchedHeadless = null, processAlive = null)
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertEquals(WindowEvidenceSource.NONE, report.source)
        assertFalse(report.verified)
        assertTrue(report.reason.contains("no launcher pid marker"), report.reason)
    }

    @Test
    @DisplayName("a browser that already exited is UNKNOWN: nothing to warn about")
    fun deadBrowserIsUnknown() {
        val report = WindowVisibilityPolicy.classify(
            WindowObservation(requestedDisplayMode = "GUI", processAlive = false, browserPid = 4242)
        )

        assertEquals(WindowVisibility.UNKNOWN, report.visibility)
        assertTrue(report.reason.contains("no longer running"), report.reason)
    }

    @Test
    @DisplayName("UNKNOWN is distinct from both success and failure on the wire")
    fun unknownIsItsOwnWireState() {
        val unknown = WindowVisibilityPolicy
            .classify(WindowObservation())
            .toMap()
        assertEquals("UNKNOWN", unknown["visibility"])
        assertEquals(false, unknown["verified"])
        assertEquals(
            3,
            setOf(WindowVisibility.VISIBLE, WindowVisibility.HIDDEN, WindowVisibility.UNKNOWN)
                .map { it.name }
                .size
        )
    }

    @Test
    @DisplayName("the headless flag match does not trip on unrelated flags")
    fun headlessFlagDetection() {
        assertTrue(SessionWindowProbe.commandLineIndicatesHeadless("chrome.exe --headless --remote-debugging-port=0"))
        assertTrue(SessionWindowProbe.commandLineIndicatesHeadless("chrome.exe --headless=new --user-data-dir=x"))
        assertTrue(SessionWindowProbe.commandLineIndicatesHeadless("chrome.exe --HEADLESS=old"))
        assertFalse(SessionWindowProbe.commandLineIndicatesHeadless("chrome.exe --remote-debugging-port=9222"))
        assertFalse(
            SessionWindowProbe.commandLineIndicatesHeadless(
                "chrome.exe --user-data-dir=C:/t/--headless-profiles/x"
            ),
            "a directory whose name contains the word is not the flag",
        )
    }
}
