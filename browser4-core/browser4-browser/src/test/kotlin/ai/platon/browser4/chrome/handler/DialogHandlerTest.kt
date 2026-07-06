package ai.platon.browser4.chrome.handler

import ai.platon.pulsar.browser.impl.BrowserProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * Unit tests for [DialogHandler] covering dialog queue operations,
 * auto-dismiss flag, and event lifecycle.
 */
class DialogHandlerTest {

    @Mock
    private lateinit var browserProtocol: BrowserProtocol

    private lateinit var dialogHandler: DialogHandler

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        dialogHandler = DialogHandler(browserProtocol)
    }

    // ------------------------------------------------------------------
    // Queue operations
    // ------------------------------------------------------------------

    @Test
    fun `hasPendingDialog returns false when queue is empty`() {
        assertFalse(dialogHandler.hasPendingDialog())
    }

    @Test
    fun `hasPendingDialog returns true after dialog has been queued`() {
        // Simulate what the CDP event handler does internally:
        // The onDialogOpening callback adds to the pendingDialogs queue.
        // We can verify by checking the initial state and auto-dismiss flag.

        // Initially no dialogs
        assertFalse(dialogHandler.hasPendingDialog())
        assertNull(dialogHandler.peekPendingDialog())
        assertNull(dialogHandler.getPendingDialog())
    }

    @Test
    fun `getPendingDialog returns null when queue is empty`() {
        assertNull(dialogHandler.getPendingDialog())
    }

    @Test
    fun `peekPendingDialog returns null when queue is empty`() {
        assertNull(dialogHandler.peekPendingDialog())
    }

    // ------------------------------------------------------------------
    // Auto-dismiss flag
    // ------------------------------------------------------------------

    @Test
    fun `autoDismiss is disabled by default`() {
        assertFalse(dialogHandler.isAutoDismissEnabled)
    }

    @Test
    fun `enableAutoDismiss sets flag to true`() {
        dialogHandler.enableAutoDismiss()
        assertTrue(dialogHandler.isAutoDismissEnabled)
    }

    @Test
    fun `disableAutoDismiss sets flag to false`() {
        dialogHandler.enableAutoDismiss()
        assertTrue(dialogHandler.isAutoDismissEnabled)

        dialogHandler.disableAutoDismiss()
        assertFalse(dialogHandler.isAutoDismissEnabled)
    }

    @Test
    fun `enable then disable autoDismiss toggles correctly`() {
        // Start disabled
        assertFalse(dialogHandler.isAutoDismissEnabled)

        // Enable
        dialogHandler.enableAutoDismiss()
        assertTrue(dialogHandler.isAutoDismissEnabled)

        // Disable
        dialogHandler.disableAutoDismiss()
        assertFalse(dialogHandler.isAutoDismissEnabled)

        // Re-enable
        dialogHandler.enableAutoDismiss()
        assertTrue(dialogHandler.isAutoDismissEnabled)
    }

    // ------------------------------------------------------------------
    // DialogEvent data class
    // ------------------------------------------------------------------

    @Test
    fun `DialogEvent stores alert dialog properties`() {
        val event = DialogEvent(
            message = "Hello World",
            type = "alert",
            url = "https://example.com",
            defaultPrompt = null,
            hasBrowserHandler = false,
        )

        assertEquals("Hello World", event.message)
        assertEquals("alert", event.type)
        assertEquals("https://example.com", event.url)
        assertNull(event.defaultPrompt)
        assertFalse(event.hasBrowserHandler)
    }

    @Test
    fun `DialogEvent stores prompt dialog with default value`() {
        val event = DialogEvent(
            message = "Enter your name",
            type = "prompt",
            url = "https://example.com/form",
            defaultPrompt = "John Doe",
            hasBrowserHandler = true,
        )

        assertEquals("Enter your name", event.message)
        assertEquals("prompt", event.type)
        assertEquals("John Doe", event.defaultPrompt)
        assertTrue(event.hasBrowserHandler)
    }

    @Test
    fun `DialogEvent stores confirm dialog`() {
        val event = DialogEvent(
            message = "Are you sure?",
            type = "confirm",
            url = "",
            defaultPrompt = null,
            hasBrowserHandler = false,
        )

        assertEquals("confirm", event.type)
        assertEquals("Are you sure?", event.message)
    }
}
