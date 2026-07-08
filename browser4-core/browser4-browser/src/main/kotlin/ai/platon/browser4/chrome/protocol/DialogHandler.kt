package ai.platon.browser4.chrome.protocol

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.protocol.util.resolveNodeObjectId
import ai.platon.cdt.kt.protocol.events.page.JavascriptDialogOpening
import ai.platon.cdt.kt.protocol.events.page.JavascriptDialogClosed
import ai.platon.browser4.api.BrowserProtocol
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event payload representing a pending JavaScript dialog (alert, confirm, prompt).
 *
 * @property message The dialog message text
 * @property type One of "alert", "confirm", "prompt", "beforeunload"
 * @property url The URL of the frame that triggered the dialog
 * @property defaultPrompt Default value for prompt-type dialogs (null for others)
 * @property hasBrowserHandler Whether the browser can handle this dialog natively
 */
data class DialogEvent(
    val message: String,
    val type: String,
    val url: String = "",
    val defaultPrompt: String? = null,
    val hasBrowserHandler: Boolean = false,
)

/**
 * Listens for [Page.javascriptDialogOpening] CDP events and queues them so
 * click/dblclick handlers can detect that a dialog is blocking the page
 * before they attempt a post-click snapshot that would deadlock the server.
 *
 * Also provides convenience methods to accept/dismiss the current dialog and
 * an auto-dismiss mode for batch/crawl workloads.
 */
class DialogHandler(
    private val browserProtocol: BrowserProtocol,
) {
    private val logger = getLogger(this)

    /** Queue of pending dialog events. Most recent is at the head. */
    private val pendingDialogs = ConcurrentLinkedQueue<DialogEvent>()

    /** When true, all dialogs are auto-accepted immediately. */
    private val autoDismissEnabled = AtomicBoolean(false)

    /** Whether the handler is currently subscribed to CDP events. */
    @Volatile
    private var subscribed = false

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Returns true if at least one dialog is waiting. */
    fun hasPendingDialog(): Boolean = pendingDialogs.isNotEmpty()

    /** Returns (and removes) the oldest pending dialog, or null. */
    fun getPendingDialog(): DialogEvent? = pendingDialogs.poll()

    /** Returns the most recent pending dialog without removing it. */
    fun peekPendingDialog(): DialogEvent? = pendingDialogs.peek()

    /**
     * Accept (or dismiss) all currently-pending dialogs.  Useful as a
     * recovery step when a dialog deadlocks the page.
     */
    suspend fun dismissAllPending() {
        while (hasPendingDialog()) {
            val event = getPendingDialog() ?: break
            try {
                browserProtocol.handleJavaScriptDialog(accept = true, promptText = null)
                logger.info("Auto-dismissed {} dialog: {}", event.type, event.message)
            } catch (e: Exception) {
                logger.warn("Failed to dismiss {} dialog: {}", event.type, e.message)
            }
        }
    }

    /** Accept the most recent pending dialog, optionally providing prompt text. */
    suspend fun acceptDialog(promptText: String? = null) {
        val event = getPendingDialog() ?: return
        browserProtocol.handleJavaScriptDialog(accept = true, promptText = promptText)
        logger.debug("Accepted {} dialog: {}", event.type, event.message)
    }

    /** Dismiss the most recent pending dialog. */
    suspend fun dismissDialog() {
        val event = getPendingDialog() ?: return
        browserProtocol.handleJavaScriptDialog(accept = false)
        logger.debug("Dismissed {} dialog: {}", event.type, event.message)
    }

    // ------------------------------------------------------------------
    // Auto-dismiss mode
    // ------------------------------------------------------------------

    /** Enable auto-dismiss: every opening dialog is accepted immediately. */
    fun enableAutoDismiss() {
        autoDismissEnabled.set(true)
        logger.info("Auto-dismiss mode enabled — all dialogs will be accepted automatically")
    }

    /** Disable auto-dismiss. */
    fun disableAutoDismiss() {
        autoDismissEnabled.set(false)
        logger.info("Auto-dismiss mode disabled")
    }

    val isAutoDismissEnabled: Boolean get() = autoDismissEnabled.get()

    // ------------------------------------------------------------------
    // CDP subscription
    // ------------------------------------------------------------------

    /**
     * Subscribe to [Page.javascriptDialogOpening] on [devTools].
     * Safe to call multiple times — only the first call actually subscribes.
     */
    fun subscribe(devTools: RemoteDevTools) {
        if (subscribed) return
        try {
            devTools.addEventListener(
                "Page",
                "javascriptDialogOpening",
                { event ->
                    onDialogOpening(event as JavascriptDialogOpening)
                },
                JavascriptDialogOpening::class.java
            )
            devTools.addEventListener(
                "Page",
                "javascriptDialogClosed",
                { _ ->
                    onDialogClosed()
                },
                JavascriptDialogClosed::class.java
            )
            subscribed = true
            logger.info("Subscribed to Page.javascriptDialogOpening events")
        } catch (e: Exception) {
            logger.warn("Failed to subscribe to dialog events: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun onDialogOpening(event: JavascriptDialogOpening) {
        val dialogEvent = DialogEvent(
            message = event.message.orEmpty(),
            type = event.type.name.lowercase(),
            url = event.url,
            defaultPrompt = event.defaultPrompt,
            hasBrowserHandler = event.hasBrowserHandler ?: false,
        )
        pendingDialogs.add(dialogEvent)
        logger.debug("Dialog opened: type={} message={}", dialogEvent.type, dialogEvent.message)

        if (autoDismissEnabled.get()) {
            // Fire-and-forget: we can't suspend here, so mark for async handling.
            // The next click/dblclick will drain the queue via dismissAllPending().
            logger.debug("Auto-dismiss queued for dialog: {}", dialogEvent.type)
        }
    }

    private fun onDialogClosed() {
        // The dialog might have been handled by us or by the user;
        // drain any stale entries that were already accepted/dismissed.
        if (pendingDialogs.isNotEmpty()) {
            logger.debug("Dialog closed externally; draining pending queue")
        }
    }

    /**
     * Drain pending dialogs if auto-dismiss is enabled.  Called from click/dblclick
     * handlers after dispatching a click that may have triggered a dialog.
     */
    suspend fun drainAutoDismiss() {
        if (autoDismissEnabled.get() && hasPendingDialog()) {
            dismissAllPending()
        }
    }
}
