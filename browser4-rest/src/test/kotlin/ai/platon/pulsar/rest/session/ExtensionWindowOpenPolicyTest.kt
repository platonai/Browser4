package ai.platon.pulsar.rest.session

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `Page.windowOpen` event the browser's popup blocker refused must not reach
 * the driver — the driver opens a tab for every window-open event, which is
 * where the stray `about:blank` tab after loading a bot-detection page comes
 * from. User-gesture popups keep their behaviour, and anything that is not a
 * debugger event is forwarded untouched.
 */
@DisplayName("extension window.open popup policy")
class ExtensionWindowOpenPolicyTest {

    /** The exact shape the extension forwards for `Page.windowOpen`. */
    private fun windowOpen(userGesture: Boolean?): String {
        val gesture = userGesture?.toString() ?: ""
        val payload = if (userGesture == null) {
            """{"url":"https://example.org/","windowName":"","windowFeatures":[]}"""
        } else {
            """{"url":"https://example.org/","windowName":"","windowFeatures":[],"userGesture":$gesture}"""
        }
        return """{"method":"chrome.debugger.onEvent","params":[{"tabId":42},"Page.windowOpen",$payload]}"""
    }

    @Test
    fun aWindowOpenRefusedByThePopupBlockerIsDropped() {
        assertTrue(ExtensionWindowOpenPolicy.isBlockedWindowOpen(windowOpen(false)))
    }

    @Test
    fun aWindowOpenTriggeredByAUserGestureIsForwarded() {
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen(windowOpen(true)))
    }

    @Test
    fun aWindowOpenWithoutAGestureFlagCountsAsBlocked() {
        // Older Chrome builds can omit the flag; the tab such a call expects was
        // either never created or is tracked through chrome.tabs.onCreated, so
        // dropping the event cannot lose a page.
        assertTrue(ExtensionWindowOpenPolicy.isBlockedWindowOpen(windowOpen(null)))
    }

    @Test
    fun anAboutBlankWindowOpenIsHandledTheSameWay() {
        val message = """{"method":"chrome.debugger.onEvent","params":[{"tabId":7},"Page.windowOpen",""" +
            """{"url":"about:blank","windowName":"","windowFeatures":[],"userGesture":false}]}"""
        assertTrue(ExtensionWindowOpenPolicy.isBlockedWindowOpen(message))
    }

    @Test
    fun otherDebuggerEventsAreForwarded() {
        val message = """{"method":"chrome.debugger.onEvent","params":[{"tabId":42},"Page.frameNavigated",""" +
            """{"frame":{"id":"F1"}}]}"""
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen(message))
    }

    @Test
    fun aPayloadThatMerelyMentionsWindowOpenIsNotAWindowOpenEvent() {
        // A console message (or any other event) can echo the string
        // "Page.windowOpen"; only params[1] names the event.
        val message = """{"method":"chrome.debugger.onEvent","params":[{"tabId":42},"Runtime.consoleAPICalled",""" +
            """{"type":"log","args":[{"type":"string","value":"Page.windowOpen"}]}]}"""
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen(message))
    }

    @Test
    fun windowOpenInAWrongPositionIsNotTreatedAsTheEvent() {
        val message = """{"method":"chrome.debugger.onEvent","params":[{"tabId":42},""" +
            """"chrome.tabs.onCreated","Page.windowOpen"]}"""
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen(message))
    }

    @Test
    fun relayMessagesThatAreNotDebuggerEventsAreForwarded() {
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"method":"ping"}"""))
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"method":"extension.initialized","params":[]}"""))
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"id":3,"result":{}}"""))
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"method":"chrome.tabs.onCreated","params":[{"url":"Page.windowOpen"}]}"""))
    }

    @Test
    fun malformedOrTruncatedInputIsForwarded() {
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("Page.windowOpen"))
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"method":"chrome.debugger.onEvent","params":["""))
        assertFalse(ExtensionWindowOpenPolicy.isBlockedWindowOpen("""{"method":"chrome.debugger.onEvent","params":[]}"""))
        assertFalse(
            ExtensionWindowOpenPolicy.isBlockedWindowOpen(
                """{"method":"chrome.debugger.onEvent","params":[{"tabId":42},"Page.windowOpen"]}"""
            )
        )
    }
}
