package ai.platon.pulsar.rest.session

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * Decides whether a CDP `Page.windowOpen` event forwarded by the Browser4
 * Chrome Extension has to be dropped before it reaches the driver.
 *
 * ## Why this policy exists
 *
 * A real Chrome/Edge window keeps its popup blocker enabled.  When a page
 * calls `window.open()` without a user gesture — the usual "popup test" on a
 * bot-detection page, or any timer-driven popup — the browser **refuses** to
 * open the window and `window.open()` returns `null`.
 *
 * Chrome DevTools Protocol still emits `Page.windowOpen` for that refused
 * call, and the Pulsar driver reacts to every such event by opening a new tab
 * (`PulsarWebDriver.onWindowOpen` → `browser.newDriver(url)` →
 * `chrome.tabs.create`).  The tab is created by the extension relay, so in a
 * real browser the blocked popup materialises as a stray `about:blank` tab
 * next to the page the user is working on.  A Browser4-managed browser does
 * not show the problem because it is launched with
 * `--disable-popup-blocking`, so `window.open()` really does open a window.
 *
 * The relay therefore has to make the decision the browser already made: an
 * event whose `userGesture` flag is not `true` is a call the popup blocker
 * refused, and forwarding it can only create a tab the page never opened.
 * Calls made with a user gesture are forwarded unchanged, so popups a real
 * click opens keep their existing behaviour.
 *
 * ## Message shape
 *
 * The extension forwards `chrome.debugger.onEvent` verbatim:
 *
 * ```json
 * {
 *   "method": "chrome.debugger.onEvent",
 *   "params": [{"tabId": 42}, "Page.windowOpen",
 *              {"url": "", "windowName": "", "windowFeatures": [], "userGesture": false}]
 * }
 * ```
 *
 * Only that shape is treated as a window-open event — a `Page.windowOpen`
 * substring inside some unrelated payload must not be mistaken for one, and
 * unparsable input is always forwarded (a relay must never swallow traffic
 * because of a parse failure).
 */
object ExtensionWindowOpenPolicy {

    /** CDP event the extension sends for `Page.windowOpen`. */
    private const val WINDOW_OPEN_EVENT = "Page.windowOpen"

    /** Chrome event wrapper the extension uses for all `chrome.debugger` events. */
    private const val DEBUGGER_EVENT_METHOD = "chrome.debugger.onEvent"

    private val mapper by lazy { pulsarObjectMapper() }

    /**
     * True when [message] is a `Page.windowOpen` event the browser's popup
     * blocker refused.
     *
     * A missing `userGesture` flag counts as "no user gesture": the tab such a
     * call expects either does not exist or was created by the browser itself
     * (and is then tracked through `chrome.tabs.onCreated`), so dropping the
     * event can never lose a page — it only prevents the relay from opening a
     * duplicate tab.
     */
    fun isBlockedWindowOpen(message: CharSequence): Boolean {
        // Cheap pre-filter: every other CDP/relay message returns here without
        // building a JSON tree, and the relay forwards a lot of them.
        if (!message.contains(WINDOW_OPEN_EVENT)) {
            return false
        }

        val root = runCatching { mapper.readTree(message.toString()) }.getOrNull() ?: return false
        if (root.get("method")?.asText() != DEBUGGER_EVENT_METHOD) {
            return false
        }

        val params = root.get("params") ?: return false
        if (!params.isArray || params.size() < 3) {
            return false
        }
        if (params.get(1)?.asText() != WINDOW_OPEN_EVENT) {
            return false
        }

        val payload = params.get(2) ?: return false
        return payload.get("userGesture")?.asBoolean(true) != true
    }
}
