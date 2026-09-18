package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.BrowserUseState
import ai.platon.pulsar.api.model.JsEvaluation
import ai.platon.pulsar.api.model.PageTarget
import ai.platon.pulsar.api.model.SnapshotOptions
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.network.HarContentMode
import ai.platon.pulsar.chrome.network.NetworkObserver
import ai.platon.pulsar.chrome.network.RobustRPC
import ai.platon.pulsar.chrome.network.RouteManager
import ai.platon.pulsar.chrome.network.TrackedNetworkRequest
import ai.platon.pulsar.chrome.protocol.Keyboard
import ai.platon.pulsar.chrome.protocol.util.withNodeObjectId
import ai.platon.pulsar.chrome.util.ChromeDriverException
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.urls.URLUtils
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Browser4-specific extension of [PulsarWebDriver].
 *
 * This class is the extension point for all browser4-specific features, bug fixes,
 * and new requirements that go beyond the core functionality provided by
 * [ai.platon.pulsar.chrome.PulsarWebDriver] from the `pulsar-browser` library.
 *
 * ## Relationship to pulsar-browser
 *
 * `pulsar-browser:4.11.2` was extracted from `browser4-browser` as a standalone
 * library to simplify this repository and reduce build time.  All types previously
 * in `ai.platon.browser4.*` now live in `ai.platon.pulsar.*` within that library.
 *
 * This class extends [PulsarWebDriver] directly — it is the **only** production
 * source file remaining in the `browser4-browser` module.  Everything else is
 * provided by the `pulsar-browser` dependency.
 *
 * ## Extension guide
 *
 * All further extensions should use [executeCdpCommand] to implement new
 * functionality.  This method provides low-level access to the Chrome DevTools
 * Protocol without coupling extension code to internal implementation details:
 *
 * ```kotlin
 * // Example: custom CDP command
 * val result = executeCdpCommand(
 *     "Page.captureScreenshot",
 *     mapOf("format" to "png", "fromSurface" to true)
 * )
 * ```
 *
 * [executeCdpCommand] delegates to [BrowserProtocol.executeCdpCommand] through
 * the robust RPC layer, giving callers the same retry / error-handling
 * guarantees as every other driver operation.
 *
 * @see PulsarWebDriver
 * @see executeCdpCommand
 */
open class Browser4WebDriver(
    uniqueID: String,
    chromeTab: BrowserTab,
    browserProtocol: BrowserProtocol,
    browser: PulsarBrowser
) : PulsarWebDriver(uniqueID, chromeTab, browserProtocol, browser) {

    init {
        // Claim the CDP event-listener slots before the base library's
        // NetworkManager registers them on first navigation: its event
        // dispatcher keeps only one listener per event key, so a listener
        // registered later would silently never fire. Per-protocol sharing
        // (see NetworkObserver.forProtocol) makes this a single registration
        // per tab no matter how many drivers wrap it.
        NetworkObserver.forProtocol(browserProtocol).preRegister()
        RouteManager.forProtocol(browserProtocol).preRegister()
    }

    /**
     * Viewport center of a drag element, plus the stable CSS path used to
     * re-locate it inside the drag script (where CDP node object ids are
     * not available for the target), a frame-residency flag, and the viewport
     * size at resolution time (used to confirm the target is actually
     * visible after an asynchronous scroll commit).
     */
    internal data class DragCenter(
        val x: Double,
        val y: Double,
        val cssPath: String,
        val inFrame: Boolean,
        val viewportWidth: Int = 0,
        val viewportHeight: Int = 0,
    )

    companion object {
        private val logger = getLogger(Browser4WebDriver::class)

        /** DOM-ready budget for the post-navigation body wait (see [waitForNavigationSettled]). */
        private const val NAVIGATION_DOM_READY_TIMEOUT_MS = 10_000L

        /** Settle delay after the post-navigation body wait (see [waitForNavigationSettled]). */
        private const val NAVIGATION_DOM_SETTLE_DELAY_MS = 1_000L

        /** Supported [typeAuto] insertion methods. */
        internal val TYPE_METHODS = setOf("auto", "chars", "exec")

        /**
         * Text length above which [typeAuto]'s `auto` method prefers one bulk
         * `execCommand('insertText')` over per-code-point typing (~150 chars ≈
         * 13-36 s at the 90-240 ms/char type cadence).
         */
        internal const val TYPE_EXEC_LONG_THRESHOLD = 150

        /** The dual-world runtime global probed by [ensurePulsarUtilsInjected]. */
        private const val PULSAR_UTILS_FUNCTION = "__pulsar_utils__"

        private val storageStateMapper: ObjectMapper = jacksonObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

        private data class StorageStatePayload(
            val cookies: List<Map<String, Any?>> = emptyList(),
            val origins: List<StorageStateOriginPayload> = emptyList(),
        )

        private data class StorageStateOriginPayload(
            val origin: String = "",
            val localStorage: List<StorageStateEntryPayload> = emptyList(),
        )

        private data class StorageStateEntryPayload(
            val name: String = "",
            val value: String = "",
        )

        private data class StorageStateLoadSummary(
            val cookies: Int,
            val origins: Int,
            val localStorageEntries: Int,
        )

        /**
         * Create a [Browser4WebDriver] from an existing [PulsarWebDriver],
         * reusing its underlying CDP connection, tab, and browser.
         *
         * Both drivers share the same [BrowserProtocol], [BrowserTab], and
         * [PulsarBrowser], so no CDP connections are torn down or duplicated.
         * Callers should unbind the original driver and bind the returned
         * instance.
         */
        fun from(driver: PulsarWebDriver): Browser4WebDriver =
            Browser4WebDriver(
                uniqueID = driver.guid,
                chromeTab = driver.chromeTab,
                browserProtocol = driver.browserProtocol,
                browser = driver.browser,
            )

        /**
         * Escape [text] for embedding as a single-quoted JavaScript string literal.
         * Escapes backslash, single quote, newline, and carriage return.
         */
        fun escapeJsString(text: String): String =
            text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

        /**
         * Escape [selector] for embedding inside a single-quoted JavaScript string
         * literal (e.g. `document.querySelector('<selector>')`).  Escapes backslash
         * and single quote.
         */
        fun escapeJsSelector(selector: String): String =
            selector.replace("\\", "\\\\").replace("'", "\\'")

        /**
         * Split [text] into complete Unicode code points, preserving surrogate pairs
         * (emoji, CJK supplementary ideographs).  The upstream `Keyboard.type()` walks
         * the string with `charAt()` (UTF-16 code units), which splits surrogate pairs
         * into invalid halves.  Iterating the returned list inserts each complete code
         * point in a single CDP `Input.insertText` call.
         */
        fun codePoints(text: String): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val charCount = Character.charCount(codePoint)
                result.add(text.substring(i, i + charCount))
                i += charCount
            }
            return result
        }

        /**
         * The `function()` body used by [fillSafe] (and the executor's fallback) to
         * set an element's value while honoring user-input constraints.  Evaluated
         * with `this` bound to the target element (via `callFunctionOn`).
         */
        fun fillValueJs(text: String): String =
            """
            function() {
                var el = this;
                if (!el) { return; }
                if (el.disabled || el.readOnly) { return; }
                var val = '${escapeJsString(text)}';
                var maxLen = el.maxLength;
                if (maxLen > 0 && val.length > maxLen) { val = val.substring(0, maxLen); }
                if (el.isContentEditable) {
                    el.textContent = val;
                } else if (el.type === 'number' || el.type === 'range') {
                    var numVal = parseFloat(val);
                    if (!isNaN(numVal)) { el.valueAsNumber = numVal; }
                    else { el.value = val; }
                } else {
                    el.value = val;
                }
                if (typeof el.focus === 'function') { el.focus(); }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            """.trimIndent()

        /**
         * Interpret the `evaluateValue(selector, "function(){ return this != null; }")`
         * probe used by the [selectOption] override.  The upstream selectOption
         * reports success even when no element matches, which silently swallows
         * typos and stale refs; the probe distinguishes:
         * - `true` — the target exists, proceed;
         * - `null` — the locator could not be resolved (missing element or a
         *   locator failure), while driver/session/transport failures throw
         *   inside evaluateValue rather than returning null;
         * - anything else (`false`) — the locator resolved to a non-element.
         *
         * @return null when the target exists, otherwise the user-facing error message.
         */
        internal fun selectOptionTargetError(selector: String, exists: Any?): String? = when (exists) {
            true -> null
            null -> "Option target could not be resolved (not found or locator failure): $selector"
            else -> "Option target not found: $selector"
        }

        /**
         * Probe used by [Browser4WebDriver.typeAuto] before choosing an insertion
         * strategy.  Evaluated with `this` bound to the target element; returns
         * `{found:false}` when the locator resolves to nothing, otherwise the
         * element kind, disabled/readOnly flags, and its CURRENT text (for the
         * verify read-back).
         */
        fun typeTargetProbeJs(): String =
            """
            function() {
                var el = this;
                if (!el) { return { found: false }; }
                var tag = (el.tagName || '').toUpperCase();
                var kind = el.isContentEditable ? 'contenteditable'
                    : (tag === 'TEXTAREA' ? 'textarea'
                    : (tag === 'INPUT' ? 'input' : 'other'));
                var text = (tag === 'INPUT' || tag === 'TEXTAREA') ? (el.value || '')
                    : ((el.innerText !== undefined ? el.innerText : '') || '');
                return { found: true, kind: kind, disabled: !!el.disabled, readOnly: !!el.readOnly, text: text };
            }
            """.trimIndent()

        /**
         * Bulk-insert [text] via `document.execCommand('insertText')` on the
         * element bound as `this`.  Focuses the element, collapses the cursor to
         * the end of the content, then inserts the whole string in one editor
         * transaction.  Returns `{ok: true}` on success or `{ok: false,
         * reason}` when the element is not editable or the command is rejected.
         *
         * Note: `execCommand` is deprecated but supported in Chrome/Edge/Firefox
         * (no removal schedule).  It synthesizes `beforeinput`/`input` events
         * WITHOUT a key event chain — sites that require keyboard events
         * (shortcuts, autocomplete) will not see it; that trade-off is why
         * typeAuto keeps per-code-point `chars` as the default for short text.
         */
        fun typeExecJs(text: String): String =
            """
            function() {
                var el = this;
                if (!el) { return { ok: false, reason: 'no element' }; }
                if (!el.isContentEditable && !(el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    return { ok: false, reason: 'target is not an editable input/textarea/contenteditable' };
                }
                if (el.disabled || el.readOnly) {
                    return { ok: false, reason: 'target is disabled or read-only' };
                }
                try { el.focus(); } catch (e) {}
                if (el.isContentEditable) {
                    try {
                        var sel = window.getSelection();
                        var range = document.createRange();
                        range.selectNodeContents(el);
                        range.collapse(false);
                        sel.removeAllRanges();
                        sel.addRange(range);
                    } catch (e) {}
                } else {
                    try { el.setSelectionRange(el.value.length, el.value.length); } catch (e) {}
                }
                var text = '${escapeJsString(text)}';
                var ok = false;
                try { ok = document.execCommand('insertText', false, text); } catch (e) { ok = false; }
                return ok ? { ok: true } : { ok: false, reason: "execCommand('insertText') returned false" };
            }
            """.trimIndent()

        /**
         * Read the current text of the element bound as `this` — `value` for
         * input/textarea, `innerText` for contenteditable — used by the
         * typeAuto verify read-back.
         */
        fun typeReadBackJs(): String =
            """
            function() {
                var el = this;
                if (!el) { return null; }
                var tag = (el.tagName || '').toUpperCase();
                if (tag === 'INPUT' || tag === 'TEXTAREA') { return el.value || ''; }
                return (el.innerText !== undefined ? el.innerText : '') || '';
            }
            """.trimIndent()

        /**
         * Normalize editor text before verification compares: NBSP → space and
         * CRLF/CR → LF (browsers and editors differ in how they store line
         * breaks).  Trailing whitespace differences are handled by the caller
         * (trimmed on both sides of the comparison).
         */
        fun normalizeEditorText(text: String): String =
            text.replace("\u00a0", " ")
                .replace("\r\n", "\n")
                .replace('\r', '\n')

        /**
         * The IIFE used by [submitFormFallback] (and the executor's fallback) to
         * submit the nearest form of the element matched by [selector] with DOM
         * keyboard events and `requestSubmit()`/`submit()` — a last-resort path
         * for JS-heavy pages that intercept both CDP Enter and the browser's
         * implicit form submission.
         */
        fun submitFormFallbackJs(selector: String): String =
            """
            (function(){
                var el=document.querySelector('${escapeJsSelector(selector)}');
                if(!el)return false;
                el.focus();
                var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
                el.dispatchEvent(new KeyboardEvent('keydown',o));
                el.dispatchEvent(new KeyboardEvent('keypress',o));
                el.dispatchEvent(new KeyboardEvent('keyup',o));
                var form=el.closest('form');
                if(form){try{form.requestSubmit();}catch(e){}form.submit();}
                return true;
            })()
            """.trimIndent()

        /**
         * The IIFE used by [consoleMessages] (and the executor's fallback) to read
         * the buffered console messages, filtering to [level] and above
         * (error=0, warn=1, info=2, log=2, debug=3).  Intercepts
         * console.log/warn/error/info/debug on first call and buffers subsequent
         * messages on `window.__b4_console`.
         */
        fun consoleMessagesJs(level: String): String =
            """
            (function() {
                if (!window.__b4_console_intercepted) {
                    window.__b4_console = window.__b4_console || [];
                    var levels = ['log', 'warn', 'error', 'info', 'debug'];
                    levels.forEach(function(lvl) {
                        var original = console[lvl];
                        console[lvl] = function() {
                            var args = Array.prototype.slice.call(arguments);
                            window.__b4_console.push({
                                level: lvl,
                                text: args.map(function(a) {
                                    return typeof a === 'object' ? JSON.stringify(a) : String(a);
                                }).join(' '),
                                timestamp: Date.now()
                            });
                            original.apply(console, arguments);
                        };
                    });
                    window.__b4_console_intercepted = true;
                }
                var minPriority = { error: 0, warn: 1, info: 2, log: 2, debug: 3 };
                var min = minPriority['$level'] !== undefined ? minPriority['$level'] : 2;
                var filtered = (window.__b4_console || []).filter(function(e) {
                    var p = minPriority[e.level] !== undefined ? minPriority[e.level] : 2;
                    return p <= min;
                });
                return JSON.stringify(filtered);
            })()
            """.trimIndent()

        /** The expression used by [consoleClear] (and the executor's fallback). */
        fun consoleClearJs(): String = "window.__b4_console = []; 'Console cleared'"

        /**
         * Normalize a cookie from a storage-state JSON payload for
         * `Network.setCookies`.  Mirrors the upstream pulsar-browser
         * normalization so states saved by `tab.saveStorageState()` round-trip
         * unchanged.
         *
         * Beyond the upstream pass-through this validates the two fields whose
         * violations make the downstream CDP layer reject the whole batch with
         * the opaque `Invalid cookie fields` error (no field attribution):
         * cookie paths that do not start with `/`, and cookie names containing
         * characters the browser will not store (`;`, `=`, whitespace/control
         * characters).  Failing here names the offending cookie so callers can
         * act on it instead of receiving the browser's generic rejection.
         *
         * @param cookie Raw cookie entry from the storage-state payload.
         * @return A map with the canonical `Network.setCookies` field set.
         */
        fun normalizeStorageStateCookie(cookie: Map<String, Any?>): Map<String, Any?> {
            val name = cookie["name"]?.toString()?.trim().orEmpty()
            require(name.isNotEmpty()) { "Storage state cookie name must not be blank" }
            // Chrome's Network.setCookies rejects cookie names containing ';',
            // '=', or whitespace/control characters ("Invalid cookie fields").
            // Validate in-repo so the error names the cookie instead of leaking
            // the browser's opaque rejection.
            require(
                name.none { it == ';' || it == '=' || it.code < 0x21 || it.code == 0x7F }
            ) {
                "Storage state cookie name '$name' contains characters the browser rejects " +
                    "(whitespace/control characters, ';', '=')"
            }

            val normalized = linkedMapOf<String, Any?>(
                "name" to name,
                "value" to (cookie["value"]?.toString() ?: ""),
            )

            cookie["url"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["url"] = it }
            cookie["domain"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["domain"] = it }
            cookie["path"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
                // Chrome rejects cookie paths that do not start with '/'
                // ("Invalid cookie fields"); validate in-repo so the error names
                // the cookie instead of leaking the browser's opaque rejection.
                require(path.startsWith('/')) {
                    "Storage state cookie '$name' has an invalid path '$path': cookie paths must start with '/'"
                }
                normalized["path"] = path
            }
            cookie["expires"]?.toString()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { normalized["expires"] = it }
            cookie["httpOnly"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["httpOnly"] = it }
            cookie["secure"]?.toString()?.toBooleanStrictOrNull()?.let { normalized["secure"] = it }
            cookie["sameSite"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized["sameSite"] = it }

            require("url" in normalized || "domain" in normalized) {
                "Storage state cookie '$name' must include either url or domain"
            }
            return normalized
        }

        /**
         * Build the JavaScript used to restore localStorage entries for a single
         * origin.  [entriesJson] must be a JSON array of `{name, value}` objects.
         */
        fun restoreLocalStorageScript(entriesJson: String): String =
            """
            (() => {
              const entries = $entriesJson;
              window.localStorage.clear();
              for (const entry of entries) {
                window.localStorage.setItem(entry.name, entry.value ?? "");
              }
              return entries.length;
            })()
            """.trimIndent()

        /**
         * JavaScript returning the active origin's `localStorage` as a JSON
         * object (`{"name": "value"}`), the capture counterpart of
         * [restoreLocalStorageScript].  Used by `saveStorageState()`.
         */
        fun captureLocalStorageScript(): String =
            "JSON.stringify(Object.fromEntries(Object.entries(window.localStorage)))"

        /** Target type for one raw CDP cookie object. */
        private val COOKIE_MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}

        /**
         * Extract the cookie list from a raw `Network.getAllCookies` CDP result.
         *
         * The command answers `{"cookies": [...]}`, but the runtime shape depends
         * on the transport: a direct CDP connection deserializes into typed CDP
         * objects, while the extension relay hands back generic JSON maps/nodes.
         * Nothing here is cast to
         * `ai.platon.cdt.kt.protocol.types.network.Cookie` — that cast is what
         * makes cookie reads fail on `attach --extension` sessions.
         *
         * @param result The raw value returned by `executeCdpCommand`.
         * @return One map per cookie; empty when [result] carries no cookie array.
         */
        fun extractCookiesFromCdpResult(result: Any?): List<Map<String, Any?>> {
            if (result == null) return emptyList()

            val root = runCatching { pulsarObjectMapper().valueToTree<JsonNode>(result) }.getOrNull()
                ?: return emptyList()
            val cookies = when {
                root.isArray -> root
                root.isObject -> root.get("cookies") ?: return emptyList()
                else -> return emptyList()
            }
            if (!cookies.isArray) return emptyList()

            return cookies.mapNotNull { node ->
                runCatching { pulsarObjectMapper().convertValue(node, COOKIE_MAP_TYPE) }.getOrNull()
            }
        }

        /**
         * True once the evaluated `location.origin` has committed to exactly
         * [targetOrigin].  Opaque-origin documents report `"null"` (or fail to
         * evaluate at all), so neither case counts as ready.
         */
        fun isDocumentOriginReady(evaluatedOrigin: String?, targetOrigin: String): Boolean =
            !evaluatedOrigin.isNullOrBlank() && evaluatedOrigin.trim() == targetOrigin

        /**
         * Parse the JSON produced by [dragCenterJs] into a [DragCenter].
         * Returns null when the value is missing, malformed, or lacks a usable
         * CSS path (which makes the element unre-locatable at drag time).
         */
        internal fun parseDragCenter(value: Any?): DragCenter? {
            val json = value as? String ?: return null
            val node = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull() ?: return null
            val x = node.get("x")?.takeIf { it.isNumber }?.asDouble() ?: return null
            val y = node.get("y")?.takeIf { it.isNumber }?.asDouble() ?: return null
            val cssPath = node.get("cssPath")?.asText()?.takeIf { it.isNotBlank() } ?: return null
            return DragCenter(
                x = x,
                y = y,
                cssPath = cssPath,
                inFrame = node.get("inFrame")?.asBoolean() ?: false,
                viewportWidth = node.get("vw")?.takeIf { it.isNumber }?.asInt() ?: 0,
                viewportHeight = node.get("vh")?.takeIf { it.isNumber }?.asInt() ?: 0,
            )
        }

        /**
         * The `function()` body evaluated with `this` bound to a drag element.
         * Returns the element's viewport center, a stable CSS path that
         * re-locates the same element from the top document at drag time, and
         * whether the element lives in a frame (drag coordinates and
         * `document.elementFromPoint` are frame-relative then, so frame
         * residents must be rejected explicitly rather than silently mis-dragged).
         */
        internal fun dragCenterJs(): String =
            """
            function() {
                if (!(this instanceof Element)) {
                    return JSON.stringify({ cssPath: '' });
                }
                const r = this.getBoundingClientRect();
                const path = [];
                let el = this;
                while (el && el.nodeType === 1) {
                    let part = el.tagName.toLowerCase();
                    if (el.id) {
                        part += '#' + CSS.escape(el.id);
                        path.unshift(part);
                        break;
                    }
                    if (el.parentElement) {
                        const sameTag = Array.prototype.filter.call(
                            el.parentElement.children,
                            (c) => c.tagName === el.tagName
                        );
                        if (sameTag.length > 1) {
                            part += ':nth-of-type(' + (sameTag.indexOf(el) + 1) + ')';
                        }
                    }
                    path.unshift(part);
                    el = el.parentElement;
                }
                return JSON.stringify({
                    x: r.left + r.width / 2,
                    y: r.top + r.height / 2,
                    cssPath: path.join(' > '),
                    inFrame: this.ownerDocument !== document,
                    vw: window.innerWidth,
                    vh: window.innerHeight
                });
            }
            """.trimIndent()

        /**
         * Build the drag sequence script executed with `this` bound to the
         * source element (via CDP `callFunctionOn`).  The target is re-located
         * by [targetCssPath] and must still be hit by the resolved viewport
         * point — otherwise the drag fails loudly instead of dispatching on an
         * unrelated element (occluded by an overlay, `pointer-events: none`,
         * or moved by an async layout shift).
         *
         * All failures are reported before any event is dispatched, so a retry
         * of the outer RPC block re-runs the sequence idempotently.
         *
         * [dropPosition] selects the drop point on the target:
         *  - `"center"` (legacy): the resolved viewport point ([targetX]/[targetY],
         *    already jittered by the caller) is used verbatim.
         *  - `"top"` / `"bottom"`: the drop point is pinned to the live
         *    top/bottom edge region of the target (`rect.top + 2` /
         *    `rect.bottom - 2`, read at dispatch time).  Child-index-based
         *    reorder handlers decide insert-before/insert-after from the drop
         *    point's y relative to child centers, so the ±2px jitter around a
         *    center point makes placement a coin flip; an edge-region point
         *    resolves deterministically (`--at bottom` of an item = insert
         *    after it, `--at top` = insert before it).  For targets shorter
         *    than ~5px the edge region is degenerate and the center point is
         *    kept.  When positioned, two intermediate `dragover` events sweep
         *    from the source point toward the drop point so handlers that
         *    track dragenter/dragover transitions observe the approach, and a
         *    final `dragover` lands exactly on the drop point.
         *
         * [delays] must contain exactly 4 randomized inter-event delays (ms)
         * for `"center"`, or exactly 6 for `"top"`/`"bottom"` (the two extra
         * delays pace the sweep events).
         */
        internal fun buildDragSequenceScript(
            targetCssPath: String,
            sourceX: Double,
            sourceY: Double,
            targetX: Double,
            targetY: Double,
            delays: List<Long>,
            dropPosition: String = "center",
        ): String {
            val positioned = dropPosition == "top" || dropPosition == "bottom"
            require(if (positioned) delays.size == 6 else delays.size == 4) {
                "drag sequence requires exactly ${if (positioned) 6 else 4} delays for drop position '$dropPosition'"
            }
            val targetJson = pulsarObjectMapper().writeValueAsString(targetCssPath)
            val positionJson = pulsarObjectMapper().writeValueAsString(dropPosition)
            // Positioned drops interpolate two sweep dragover events between
            // the source point and the final drop point.
            val sweepBlock = if (positioned) """
                    const sweep = [ { t: 0.34, delay: ${delays[2]} }, { t: 0.67, delay: ${delays[3]} } ];
                    for (const step of sweep) {
                        const t = step.t;
                        const ix = $sourceX + (dropX - $sourceX) * t;
                        const iy = $sourceY + (dropY - $sourceY) * t;
                        fire(document.elementFromPoint(ix, iy) || hit, 'dragover', ix, iy);
                        await sleep(step.delay);
                    }
            """.trimIndent() else ""
            // After dragenter the event pace depends on the drop mode: center
            // sleeps once before the single dragover; positioned sleeps per
            // sweep step, then once more before the final dragover.
            val (dragoverIndex, dropIndex) = if (positioned) 4 to 5 else 2 to 3
            // CDP Runtime.callFunctionOn requires a *function declaration*, not
            // an expression — an IIFE is rejected with "Given expression does
            // not evaluate to a function".  `async function()` + awaitPromise
            // gives us the same async sequencing inside a valid declaration.
            return """
                async function() {
                    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
                    const source = this;
                    if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
                        return JSON.stringify({
                            ok: false,
                            error: 'HTML5 drag-and-drop APIs are not available in the current page context'
                        });
                    }
                    const target = document.querySelector($targetJson);
                    if (!target) {
                        return JSON.stringify({
                            ok: false,
                            error: 'Target element was not found at drag time'
                        });
                    }
                    var dropX = $targetX;
                    var dropY = $targetY;
                    if ($positionJson === 'top' || $positionJson === 'bottom') {
                        const targetRect = target.getBoundingClientRect();
                        if (targetRect.height > 4) {
                            dropY = $positionJson === 'bottom' ? targetRect.bottom - 2 : targetRect.top + 2;
                        }
                    }
                    const hit = document.elementFromPoint(dropX, dropY);
                    // The hit must be the target itself or one of its
                    // descendants (b.contains(a): the target contains the
                    // hit).  A hit on an *ancestor* means the target is not
                    // hittable at that point (pointer-events:none, or it
                    // moved) — dispatching there would silently drop onto the
                    // wrong element.
                    const related = (a, b) => a === b || (!!a && !!b && b.contains(a));
                    if (!related(hit, target)) {
                        return JSON.stringify({
                            ok: false,
                            error: 'Target element is occluded or moved: the resolved point is covered by another element'
                        });
                    }
                    const dataTransfer = new DataTransfer();
                    const fire = (element, type, clientX, clientY) => {
                        const event = new DragEvent(type, {
                            bubbles: true,
                            cancelable: true,
                            composed: true,
                            dataTransfer,
                            clientX,
                            clientY
                        });
                        element.dispatchEvent(event);
                    };
                    fire(source, 'dragstart', $sourceX, $sourceY);
                    await sleep(${delays[0]});
                    fire(hit, 'dragenter', dropX, dropY);
                    await sleep(${delays[1]});
            $sweepBlock
                    fire(hit, 'dragover', dropX, dropY);
                    await sleep(${delays[dragoverIndex]});
                    fire(hit, 'drop', dropX, dropY);
                    await sleep(${delays[dropIndex]});
                    fire(source, 'dragend', dropX, dropY);
                    return JSON.stringify({ ok: true });
                }
            """.trimIndent()
        }

        /**
         * Interpret the `callFunctionOn` result value of a drag script.
         * Returns null on success, or a user-facing error message.
         */
        internal fun dragScriptErrorMessage(result: Any?): String? {
            val json = result as? String ?: return "Failed to execute drag script"
            val parsed = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull()
            if (parsed?.get("ok")?.asBoolean() == true) {
                return null
            }
            return parsed?.get("error")?.asText() ?: "Unknown drag failure"
        }

        /**
         * The `function()` body evaluated with `this` bound to an element.
         * Returns a comparable fingerprint of the element's DOM position
         * (parent key + child index + sibling count).  The element is bound
         * via its CDP object id, which stays valid across reparenting, so the
         * fingerprint can be re-read after a dispatch to verify the element
         * actually moved.
         */
        internal fun dragPositionFingerprintJs(): String =
            """
            function() {
                if (!(this instanceof Element)) {
                    return JSON.stringify({ ok: false });
                }
                const parent = this.parentElement;
                if (!parent) {
                    return JSON.stringify({ ok: false });
                }
                const kids = Array.prototype.filter.call(parent.children, (c) => c.nodeType === 1);
                const parentKey = parent.tagName.toLowerCase() + (parent.id ? '#' + parent.id : '');
                return JSON.stringify({
                    ok: true,
                    parentKey: parentKey,
                    index: kids.indexOf(this),
                    total: kids.length
                });
            }
            """.trimIndent()

        /**
         * The `function()` body evaluated with `this` bound to the dragged
         * element (re-located by its original locator after the drag).  Returns
         * the element's tag/id and its position among its siblings' parent.
         */
        internal fun dragPositionReportJs(): String =
            """
            function() {
                if (!(this instanceof Element)) {
                    return JSON.stringify({ ok: false });
                }
                const parent = this.parentElement;
                if (!parent) {
                    return JSON.stringify({ ok: false });
                }
                const kids = Array.prototype.filter.call(parent.children, (c) => c.nodeType === 1);
                return JSON.stringify({
                    ok: true,
                    tag: this.tagName.toLowerCase(),
                    id: this.id || '',
                    parentTag: parent.tagName.toLowerCase(),
                    parentId: parent.id || '',
                    index: kids.indexOf(this),
                    total: kids.length
                });
            }
            """.trimIndent()

        /**
         * Render the JSON produced by [dragPositionReportJs] into a
         * human-readable placement summary, or null when the value is missing,
         * malformed, or reports `ok: false`.
         */
        internal fun parseDragPositionReport(value: Any?): String? {
            val json = value as? String ?: return null
            val node = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull() ?: return null
            if (node.get("ok")?.asBoolean() != true) {
                return null
            }
            val tag = node.get("tag")?.asText()?.takeIf { it.isNotBlank() } ?: "element"
            val id = node.get("id")?.asText().orEmpty()
            val parentTag = node.get("parentTag")?.asText()?.takeIf { it.isNotBlank() } ?: "element"
            val parentId = node.get("parentId")?.asText().orEmpty()
            val index = node.get("index")?.takeIf { it.isNumber }?.asInt() ?: return null
            val total = node.get("total")?.takeIf { it.isNumber }?.asInt() ?: return null
            if (total <= 0 || index < 0 || index >= total) {
                return null
            }
            val self = "$tag${if (id.isNotEmpty()) "#$id" else ""}"
            val parent = "$parentTag${if (parentId.isNotEmpty()) "#$parentId" else ""}"
            return "Dropped $self as child ${index + 1} of $total in $parent"
        }

        // ---------------------------------------------------------------------
        // Capture annotations: visual information (vi) and the normalized URI
        //
        // The runtime materializes `vi` bounding boxes and the capture meta links
        // only in the HTML it serializes: the boxes live in a WeakMap that
        // `compute()` fills, the links in `_captureMetaLinks`, and the serializer
        // injects both while it walks the document.  Every read that skips those
        // steps therefore loses the annotation silently.  The statuses below let
        // the driver tell "nothing to do" apart from "nothing that can be done",
        // so the failure is logged instead of being smuggled into an unannotated
        // HTML string.
        // ---------------------------------------------------------------------

        /** [viDataStatusJs] status: the document already has vi data, or has it now. */
        internal const val VI_DATA_COMPUTED = "computed"

        /** [viDataStatusJs] status: the document cannot be annotated yet (no body, or not parsed). */
        internal const val VI_DATA_NOT_READY = "not-ready"

        /** [viDataStatusJs] status: the Browser4 runtime (and its serializer) is not on this tab. */
        internal const val VI_DATA_UNAVAILABLE = "unavailable"

        /** [viDataStatusJs] status: the runtime is present but produced no vi data. */
        internal const val VI_DATA_FAILED = "failed"

        /**
         * The `rel` of the capture meta link that records the page URL, matching
         * `AppConstants.PULSAR_DOCUMENT_NORMALIZED_URI`.  The serializer writes it
         * into the serialized `<head>` so an offline copy of the page stays
         * self-describing.
         */
        internal const val CAPTURE_META_LINK_REL = "normalizedURI"

        /**
         * Field separator of the [viDataStatusJs] result.  A control character
         * cannot occur in a status, a URL or an attribute value, so the single
         * evaluation result splits without a JSON round trip.
         *
         * The generated JS spells it out as the `\u0001` escape sequence (see
         * [fieldSeparatorJsEscape]) so the evaluated source stays plain ASCII.
         */
        internal const val VI_DATA_FIELD_SEPARATOR = "\u0001"

        /**
         * [VI_DATA_FIELD_SEPARATOR] as it appears inside the generated JS source,
         * i.e. the escape sequence the JavaScript engine turns back into the
         * control character at evaluation time.
         */
        internal val fieldSeparatorJsEscape: String =
            "\\u" + VI_DATA_FIELD_SEPARATOR.first().code.toString(16).padStart(4, '0')

        /**
         * Probe evaluated on the live tab: the vi (visual-information) state of
         * the document — computing the data when the document has none yet — the
         * `normalizedURI` currently stored for serialization, and the live
         * document URL.
         *
         * Evaluated as a single CDP call so the check and the computation cannot
         * be interleaved by a navigation.  The status is one of the `VI_DATA_*`
         * constants rather than a boolean so the caller can log the difference
         * between an expected no-op and a real failure; the stored link and the
         * document URL let the caller decide — without a second round trip —
         * whether the link has to be (re)stored before serializing.
         */
        internal fun viDataStatusJs(): String =
            """
            (function () {
              var u = window.$PULSAR_UTILS_FUNCTION;
              var url = document.URL || '';
              if (!u || typeof u.getAnnotatedHTML !== 'function') {
                return '$VI_DATA_UNAVAILABLE$fieldSeparatorJsEscape$fieldSeparatorJsEscape' + url;
              }
              var links = u._captureMetaLinks || {};
              var stored = links['$CAPTURE_META_LINK_REL'] || '';
              var status;
              if (u._viDataComputed === true) {
                status = '$VI_DATA_COMPUTED';
              } else if (typeof u.compute !== 'function') {
                status = '$VI_DATA_UNAVAILABLE';
              } else if (!document.body || !document.body.firstChild) {
                status = '$VI_DATA_NOT_READY';
              } else {
                try { u.compute(); } catch (e) { /* reported as failed below */ }
                status = u._viDataComputed === true ? '$VI_DATA_COMPUTED' : '$VI_DATA_FAILED';
              }
              return status + '$fieldSeparatorJsEscape' + stored + '$fieldSeparatorJsEscape' + url;
            })()
            """.trimIndent()

        /**
         * JS storing [normalizedUri] as the page URL the annotated serializer
         * writes into the serialized `<head>`.
         *
         * Best effort by construction: the value lands in the runtime's capture
         * meta links (no DOM mutation), and the serializer emits it only while
         * serializing, so the live page stays untouched.
         */
        internal fun storeCaptureMetaLinkJs(normalizedUri: String): String =
            """
            (function () {
              var u = window.$PULSAR_UTILS_FUNCTION;
              if (!u) return false;
              u._captureMetaLinks = u._captureMetaLinks || {};
              u._captureMetaLinks['$CAPTURE_META_LINK_REL'] = '${escapeJsString(normalizedUri)}';
              return true;
            })()
            """.trimIndent()

        /**
         * The vi state of the live document as reported by [viDataStatusJs]:
         * the [status], the page URL currently stored for serialization
         * ([storedUri], blank when none is stored), and the live document URL
         * ([documentUrl]).
         */
        internal data class ViDataProbe(val status: String, val storedUri: String, val documentUrl: String)

        /**
         * Parse a [viDataStatusJs] result, or null when the evaluation produced
         * something unexpected (a truncated or non-string result).
         */
        internal fun parseViDataProbe(value: Any?): ViDataProbe? {
            val text = value as? String ?: return null
            val parts = text.split(VI_DATA_FIELD_SEPARATOR)
            if (parts.size != 3) return null
            return ViDataProbe(parts[0], parts[1], parts[2])
        }

        /**
         * Whether a vi failure on [documentUrl] still has to be reported, given
         * the URL of the last reported failure.  One warning per document keeps
         * a page that cannot be annotated from flooding the log on every read.
         */
        internal fun shouldReportViFailure(lastReportedUrl: String?, documentUrl: String): Boolean =
            lastReportedUrl != documentUrl
    }

/**
 * Where on the target element a drag should drop.  `top`/`bottom` pin the
 * drop point to the target's live top/bottom edge region, making
 * insert-before/insert-after deterministic on child-index-based reorder
 * lists (the legacy `center` point sits exactly on the insert boundary, so
 * ±2px jitter decides the outcome).
 */
internal enum class DragDropPosition(val key: String) {
    CENTER("center"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        /** Resolve [key] (case-insensitive) to a [DragDropPosition]. */
        fun from(key: String): DragDropPosition =
            entries.firstOrNull { it.key == key.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "Unknown drag position '$key' (expected one of: ${entries.joinToString { it.key }})"
                )
    }
}

    // ---------------------------------------------------------------------------
    // Extension surface
    //
    // Override or add methods here for browser4-specific behaviour.
    //
    // Use executeCdpCommand(method, params) inherited from PulsarWebDriver
    // (which delegates to BrowserProtocol via RobustRPC) for any low-level
    // CDP integration.
    // ---------------------------------------------------------------------------

    /**
     * A [RobustRPC] instance for this driver.
     *
     * The upstream [PulsarWebDriver.rpc] is `private`, so overrides cannot reuse
     * it.  This instance wraps the browser4-specific operations below so that
     * they keep the same retry / health-check / CDT-agent-recovery guarantees
     * as the rest of the driver (see [RobustRPC]).  Failure accounting is
     * per-instance, so it is tracked independently from the parent's counters.
     */
    private val rpc = RobustRPC(this)

    /**
     * Network observer for this tab, shared by every driver wrapping the same
     * tab protocol (see [NetworkObserver.forProtocol]).
     *
     * Network tracking and HAR recording are opt-in: the CDP `Network` domain
     * is enabled on the first `network*`/`har*` call, so tabs that never use
     * the feature pay no overhead. The event listeners themselves are
     * registered eagerly at construction so they claim the dispatcher slot
     * before the base library's `NetworkManager` does on first navigation.
     */
    private val networkObserver: NetworkObserver by lazy {
        NetworkObserver.forProtocol(browserProtocol)
    }

    /**
     * Request router for this tab (CDP `Fetch` interception), shared per tab
     * protocol like the network observer; the `Fetch.requestPaused` listener
     * is registered eagerly at construction for the same reason.
     */
    private val routeManager: RouteManager by lazy {
        RouteManager.forProtocol(browserProtocol)
    }

    /**
     * List network requests tracked for this tab, optionally filtered.
     *
     * The CDP `Network` domain is enabled on first use; requests observed
     * afterwards are retained in a bounded in-memory store (oldest evicted).
     *
     * @param filter Only requests whose URL contains this text (case-insensitive).
     * @param type Only requests whose CDP resource type is in this comma-separated list (e.g. `xhr,fetch`).
     * @param method Only requests with this HTTP method (case-insensitive).
     * @param status Status filter: exact code (`200`), wildcard (`2xx`), or range (`400-499`).
     * @param clear When true, drop all tracked requests first.
     * @return The matching requests in observation order.
     */
    suspend fun networkRequests(
        filter: String? = null,
        type: String? = null,
        method: String? = null,
        status: String? = null,
        clear: Boolean = false,
    ): List<TrackedNetworkRequest> {
        networkObserver.ensureEnabled()
        // Strip captured bodies: list results carry metadata only; bodies are
        // retrieved through networkRequestDetail.
        return networkObserver.networkRequests(filter, type, method, status, clear).map { it.withoutBody() }
    }

    /**
     * Full detail of one tracked network request, including headers, timing,
     * and the response body (fetched on demand when available).
     *
     * @param requestId The CDP network request id, as shown by [networkRequests].
     * @throws IllegalArgumentException when the request id is unknown.
     */
    suspend fun networkRequestDetail(requestId: String): Map<String, Any?> {
        networkObserver.ensureEnabled()
        return networkObserver.networkRequestDetail(requestId)
    }

    /**
     * Start a HAR recording session on this tab.
     *
     * @param contentMode Which response bodies to embed in the HAR: `none`,
     * `text` (text-like MIME types only), or `all` (binary base64-encoded).
     * @return Recording metadata.
     */
    suspend fun harStart(contentMode: String = "none"): Map<String, Any?> {
        val mode = HarContentMode.parse(contentMode)
        return networkObserver.harStart(mode)
    }

    /**
     * Stop the active HAR recording and build the HAR 1.2 document from all
     * requests observed so far.
     *
     * @return `{ recording, contentMode, entries, har }` where `har` is the
     * HAR document (serialize to JSON to get a `.har` file).
     */
    suspend fun harStop(): Map<String, Any?> {
        return networkObserver.harStop()
    }

    /**
     * Route matching requests to a mock response or abort them, via the CDP
     * `Fetch` domain (agent-browser compatible).
     *
     * @param urlPattern URL pattern: `*` matches all; plain text matches URLs
     * containing it; `*` globs are supported (e.g. `**` + `/api/users`).
     * @param abort When true, matching requests fail instead of being sent.
     * @param body Mock response body (plain text; JSON strings work as-is).
     * @param contentType Content-Type for the mock response (e.g. `application/json`).
     * @param resourceType Only intercept requests of these CDP resource types
     * (comma-separated, e.g. `xhr,fetch`); empty matches all.
     * @return `{ "routed": urlPattern }`.
     */
    suspend fun networkRoute(
        urlPattern: String,
        abort: Boolean = false,
        body: String? = null,
        contentType: String? = null,
        resourceType: String? = null,
    ): Map<String, Any?> {
        require(urlPattern.isNotBlank()) { "networkRoute requires a non-blank urlPattern" }
        require(abort || body != null) {
            "networkRoute requires at least one action: --abort or --body"
        }
        val response = if (body != null) {
            RouteManager.RouteResponse(body = body, contentType = contentType)
        } else {
            null
        }
        val types = resourceType?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return routeManager.route(urlPattern, response, abort, types)
    }

    /**
     * Remove routes. Without [urlPattern] every route is removed and Fetch
     * interception is disabled.
     *
     * @return `{ "unrouted": urlPattern | "all" }`.
     */
    suspend fun networkUnroute(urlPattern: String? = null): Map<String, Any?> {
        return routeManager.unroute(urlPattern)
    }

    /**
     * Capture the browser/page state, degrading gracefully when the page is unusable.
     *
     * The upstream [PulsarWebDriver.browserUseState] can throw a `NullPointerException`
     * when it is invoked on a driver whose browser/page has been torn down.  Agent
     * sessions reuse the same bound driver across tasks, so a task that closed the
     * browser leaves the next task's driver pointing at a dead page — and the NPE
     * would otherwise crash the whole agent run.  Return the dummy state instead so
     * the agent can still proceed (and typically recover by navigating).
     *
     * @param pageTarget Optional page target (defaults to the active page).
     * @param snapshotOptions Options controlling the depth/verbosity of the snapshot.
     */
    @Throws(WebDriverException::class)
    override suspend fun browserUseState(
        pageTarget: PageTarget,
        snapshotOptions: SnapshotOptions
    ): BrowserUseState {
        return try {
            super.browserUseState(pageTarget, snapshotOptions)
        } catch (e: Exception) {
            logger.warn("browserUseState degraded ({}); returning dummy state", e.message)
            BrowserUseState.DUMMY
        }
    }

    /**
     * Click on an element identified by [selector] with optional [button] and [count].
     *
     * Extends [PulsarWebDriver.click] with a [button] parameter for right-click,
     * middle-click, and other mouse buttons.  When [button] is `null` or `"left"`,
     * this delegates to [click] for standard left-click behaviour (best-effort
     * focus → parent click, which scrolls into view and dispatches the click).
     *
     * For non-left buttons the element is focused and scrolled into view before
     * dispatching [mouseDown] / [mouseUp] at the element's clickable point, matching
     * the parent's pre-click sequence without duplicating its internals.
     *
     * @param selector A CSS selector, XPath, or "backend:nodeId" locator for the target element.
     * @param count Number of consecutive clicks (1 = single, 2 = double, etc.).
     * @param button Mouse button name: `"left"`, `"right"`, `"middle"`, `"back"`, or `"forward"`.
     *        Defaults to `"left"` when `null`.
     * @throws WebDriverException if the element cannot be found or interacted with.
     */
    @Throws(WebDriverException::class)
    suspend fun click(selector: String, count: Int = 1, button: String? = null) {
        if (button == null || button == "left") {
            focusElementBeforeClick(selector)
            super.click(selector, count)
            return
        }

        // Match the parent click's dialog handling: drain any stale dialog before
        // the operation (a leftover dialog blocks CDP health checks), and auto-accept
        // any dialog the click opens (when autoDismissDialogs is enabled).
        dialogHandler.dismissAllPending()
        try {
            rpc.invokeOnElement(selector, "click", scrollIntoView = true) {
                // Scroll the element into view so it is interactable.  Right-click and
                // other non-left buttons do not require the element to be focusable
                // (e.g. <div> elements without tabindex), so focus is best-effort.
                try {
                    page.focusOnSelector(selector)
                } catch (e: Exception) {
                    // Element is not focusable — that's fine for non-left clicks.
                }

                // Resolve the element's clickable point after scroll.
                val point = clickablePoint(selector)
                    ?: throw WebDriverException("Element not found or not clickable: $selector")

                // Move to the element, then dispatch `count` press+release pairs with
                // the requested button.  Each pair carries an incrementing detail
                // (1..count) so a count of 2 produces a proper double-click sequence.
                mouseMove(point.x, point.y)
                repeat(count) { i ->
                    mouseDown(button, i + 1)
                    mouseUp(button, i + 1)
                }
            }
        } finally {
            dialogHandler.drainAutoDismiss()
        }
    }

    /**
     * Best-effort focus of [selector] before a left-click.  The parent Windows
     * click implementation dispatches a synthetic DOM click, which — unlike a
     * real mouse click — never transfers focus.  Non-focusable targets
     * (e.g. a `<div>` without `tabindex`) are unaffected.
     */
    private suspend fun focusElementBeforeClick(selector: String) {
        try {
            page.focusOnSelector(selector)
        } catch (e: Exception) {
            // Element is not focusable — that's fine for the click itself.
        }
    }

    // ---------------------------------------------------------------------------
    // Click & double-click fixes — upstream pulsar-browser:4.11.x dispatches
    // Windows clicks through pure DOM JS (dispatchDomClick), which never moves
    // the mouse.  Two consequences: CSS :hover stays stuck on whatever element
    // a previous operation hovered (not the element being clicked), and a
    // click whose handler opens a native JS dialog (alert/confirm/prompt)
    // blocks the CDP evaluate until the dialog is handled — the caller hangs
    // for its full HTTP timeout with no explanation.  These overrides move the
    // pointer to the click target first (so hover state reflects the element
    // under the pointer when the click lands) and watch the driver's
    // [dialogHandler] while the click is in flight, responding immediately
    // when the page opens a dialog instead of hanging.
    // ---------------------------------------------------------------------------

    /**
     * Driver-scoped scope for parked click dispatches.  A click that opened a
     * native dialog is *parked* here — still awaiting the CDP response that
     * arrives once the dialog is handled — while the click method returns an
     * early, actionable error.  The scope intentionally outlives any single
     * request: the parked click finishes when the user later runs
     * dialog-accept / dialog-dismiss, so the page state the click was meant to
     * trigger still lands.
     */
    private val clickWatchScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Move the pointer to the click target's center before dispatching the
     * click, so the pointer physically rests on the element when its click
     * events fire.  The upstream Windows click path never moves the mouse
     * (pure DOM JS), so without this step CSS `:hover` keeps reflecting the
     * previous operation's element — stale highlights and hover tooltips
     * persist across clicks and snapshots taken right after a click miss the
     * tooltip that a real user would see.
     *
     * Uses the drag-style instant scroll + visibility poll, so the resolved
     * center is stable before the pointer moves.  Best-effort: when the
     * element cannot be resolved or never becomes visible, the pointer is
     * left alone and the upstream click proceeds as before.
     */
    private suspend fun movePointerToClickTarget(selector: String) {
        runCatching {
            evaluateValue(
                selector,
                "function(){ this.scrollIntoView({ block: 'center', behavior: 'instant' }); return true; }",
            )
        }
        // Poll until the resolved center lands inside the viewport (the scroll
        // commits asynchronously on the renderer).  Never re-scrolls inside
        // the poll — that would restart any in-flight scroll animation.
        var center: DragCenter? = null
        for (attempt in 0 until 15) {
            center = resolveDragCenter(selector)
            if (center == null) {
                return
            }
            val c = center ?: return
            val visible = c.viewportWidth <= 0 || (
                c.x >= 0 && c.y >= 0 && c.x <= c.viewportWidth && c.y <= c.viewportHeight
                )
            if (visible) {
                break
            }
            delay(150)
        }
        val point = center ?: return
        val visible = point.viewportWidth <= 0 || (
            point.x >= 0 && point.y >= 0 && point.x <= point.viewportWidth && point.y <= point.viewportHeight
            )
        if (visible) {
            mouseMove(point.x, point.y)
        }
    }

    /**
     * Run a click-family [block] in the background while watching the driver's
     * [dialogHandler] for a native dialog opened by the click.
     *
     * A dialog-opening click blocks the renderer's main thread at the
     * `alert()`/`confirm()`/`prompt()` call, so the CDP evaluation never
     * returns until the dialog is handled — without this watch the caller
     * hangs for its full HTTP timeout.  Here the click is parked in
     * [clickWatchScope] and the watch responds as soon as the dialog event
     * arrives:
     *
     *  - auto-dismiss mode ([DialogHandler.isAutoDismissEnabled]): the dialog
     *    is accepted immediately, so the parked click completes and the
     *    overall click succeeds (this makes `--auto-dismiss-dialogs` actually
     *    work for dialog-triggering clicks, which the upstream drain — only
     *    run after a completed click — cannot unblock).
     *  - otherwise a [WebDriverException] with an actionable message is
     *    thrown *without* cancelling the parked click.  The CDP evaluation is
     *    blocked on the dialog and stays in flight; a later
     *    dialog-accept/dialog-dismiss unblocks it, the click completes, and
     *    the page state updates as if the click had never been interrupted.
     *
     * Stale dialogs are drained first so the watch can only ever report a
     * dialog opened by *this* click (the upstream implementation repeats the
     * drain at the start of every click).
     */
    private suspend fun <T> withDialogWatch(action: String, block: suspend () -> T): T {
        dialogHandler.dismissAllPending()
        val outcome = CompletableDeferred<Result<T>>()
        clickWatchScope.launch {
            outcome.complete(runCatching { block() })
        }
        while (true) {
            val dialog = dialogHandler.peekPendingDialog()
            if (dialog != null) {
                if (dialogHandler.isAutoDismissEnabled) {
                    // Auto-dismiss mode: accept the dialog so the parked click
                    // can resume; keep watching in case the handler opens more.
                    dialogHandler.acceptDialog()
                    delay(150)
                    continue
                }
                // The page opened a dialog and the click is paused on it.
                // Report promptly (the caller would otherwise hang for its
                // full HTTP timeout) but leave the click parked — see the
                // KDoc above.
                val type = dialog.type
                val dialogMessage = dialog.message.takeIf { it.isNotBlank() }
                val detail = if (dialogMessage != null) {
                    val clipped = if (dialogMessage.length > 100) dialogMessage.take(100) + "..." else dialogMessage
                    ": \"$clipped\""
                } else {
                    ""
                }
                throw WebDriverException(
                    "The $action opened a native $type dialog$detail and is paused until the dialog is handled. " +
                        "Run 'dialog-accept' (or 'dialog-dismiss') to let the click finish; do not re-run the click.",
                    driver = this@Browser4WebDriver,
                )
            }
            if (outcome.isCompleted) {
                break
            }
            delay(150)
        }
        return outcome.await().getOrThrow()
    }

    /**
     * Left-click [count] times on [selector], with the pointer moved onto the
     * element first and native dialogs reported as they open (see
     * [movePointerToClickTarget] and [withDialogWatch]).
     */
    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, count: Int) {
        movePointerToClickTarget(selector)
        withDialogWatch("click") { super.click(selector, count) }
    }

    /**
     * Click [selector] with a [modifier] key held; see [click] for the
     * pointer-move and dialog-watch behaviour.
     */
    @Throws(WebDriverException::class)
    override suspend fun click(selector: String, modifier: String) {
        movePointerToClickTarget(selector)
        withDialogWatch("click") { super.click(selector, modifier) }
    }

    /**
     * Double-click [selector] (with an optional [modifier]); see [click] for
     * the pointer-move and dialog-watch behaviour.
     */
    @Throws(WebDriverException::class)
    override suspend fun dblclick(selector: String, modifier: String) {
        movePointerToClickTarget(selector)
        withDialogWatch("dblclick") { super.dblclick(selector, modifier) }
    }

    // ---------------------------------------------------------------------------
    // Keyboard fixes — the upstream pulsar-browser:4.11.2 Keyboard / PulsarWebDriver
    // lack the fixes from c9e32e070 (PR #564).  These overrides bridge the gap
    // until a new pulsar-browser release incorporates them upstream.
    // ---------------------------------------------------------------------------

    /**
     * Shared [Keyboard] instance for this driver.
     *
     * The upstream [PulsarWebDriver.keyDown] / [keyUp] dispatch stateless JS
     * [KeyboardEvent]s, and the upstream [PulsarWebDriver.press] reads the
     * modifier state from a *private* Keyboard instance that keyDown never
     * touches.  Keeping one [Keyboard] for keyDown/keyUp/press here makes
     * `Keyboard.pressedModifiers` track held modifiers so that sequences like
     * `keyDown("Control")` → `press("a")` produce DOM events with
     * `ctrlKey: true`.
     */
    private val keyboard: Keyboard by lazy { Keyboard(browserProtocol) }

    /**
     * Dispatch a keyDown for [key] through the stateful [Keyboard.down]
     * path so held modifiers (Control, Alt, Meta, Shift) are tracked and
     * applied to subsequent [press] calls.
     */
    override suspend fun keyDown(key: String) {
        rpc.invokeOnPage("keyDown") { keyboard.down(key) }
    }

    /**
     * Dispatch a keyUp for [key] through the stateful [Keyboard.up] path,
     * matching [keyDown] so released modifiers are cleared again.
     */
    override suspend fun keyUp(key: String) {
        rpc.invokeOnPage("keyUp") { keyboard.up(key) }
    }

    /**
     * Press [key], optionally on the element identified by [selector], using
     * the shared [keyboard] so that modifiers held via [keyDown] are applied.
     *
     * When [selector] is provided the element is focused first, and the
     * cursor is moved to the end ONLY for single printable characters —
     * navigation keys (Home, End, ArrowLeft, Delete, …) preserve the current
     * cursor position so chains like Home→Delete work as expected.
     *
     * Mirrors the parent's Enter-key safety net: a CDP-dispatched `Enter` does
     * not reliably trigger the browser's implicit form submission (HTML
     * §4.10.2.2), so [trySubmitFormOnEnter] explicitly submits the nearest form.
     */
    @Throws(WebDriverException::class)
    override suspend fun press(key: String, selector: String?) {
        if (selector.isNullOrBlank()) {
            rpc.invokeOnPage("press") {
                keyboard.press(key, randomDelayMillis("press"))
                if (key == "Enter") trySubmitFormOnEnter()
                gap("press")
            }
            return
        }

        rpc.invokeOnElement(selector, "press", focus = true) {
            if (key.length == 1 && !Character.isISOControl(key[0])) {
                try {
                    evaluate(
                        """
                        (function(){
                            var el = document.querySelector('${escapeJsSelector(selector)}');
                            if (!el) return;
                            if (typeof el.setSelectionRange === 'function') {
                                el.setSelectionRange(99999, 99999);
                            }
                        })()
                        """.trimIndent()
                    )
                } catch (_: Exception) {
                    // Non-text elements (buttons, divs) don't support setSelectionRange.
                    // Silently ignore — the press will still work for non-text targets.
                }
            }

            keyboard.press(key, randomDelayMillis("press"))
            if (key == "Enter") trySubmitFormOnEnter()
            gap("press")
        }
    }

    /**
     * Type text into the element identified by [selector] with correct Unicode
     * surrogate-pair handling.
     *
     * The upstream [PulsarWebDriver.type] delegates to `Keyboard.type()` which
     * walks the string with `charAt()`, splitting surrogate pairs (emoji, CJK
     * supplementary ideographs) into invalid halves that cause CDP
     * `Input.insertText` to fail.  This method walks by code point via
     * [String.codePointAt] and inserts each complete code point in a single
     * CDP call.
     *
     * For ASCII-only text this is functionally identical to the parent
     * implementation; callers that know their text is BMP-safe may prefer
     * to delegate directly.
     */
    @Throws(WebDriverException::class)
    suspend fun typeSafe(text: String, selector: String) {
        // Focus the element without repositioning the cursor.
        // The parent type(text, selector) always clicks the right edge +
        // setSelectionRange(99999,99999), which breaks chained operations
        // like ArrowLeft→type that rely on preserving cursor position.
        // insertText() respects the existing cursor position, so text
        // appends when cursor is at end and inserts when cursor was moved.
        rpc.invokeOnElement(selector, "type", focus = true) {
            // Type code point by code point — avoids the charAt() surrogate-splitting
            // bug in the upstream Keyboard.type().
            for (charString in codePoints(text)) {
                if (Character.isISOControl(charString.codePointAt(0))) {
                    press(charString)
                } else {
                    browserProtocol.insertText(charString)
                }

                if (charString.length > 1) {
                    // Supplementary character — give the browser a little more time
                    delay(randomDelayMillis("type") * 2)
                } else {
                    delay(randomDelayMillis("type"))
                }
            }
        }
    }

    /**
     * Type [text] into the element identified by [selector] with a pluggable
     * insertion strategy and optional read-back verification.
     *
     * ## Methods
     * - `chars` — per-code-point `Input.insertText` with randomized 90-240ms
     *   delays (the classic [typeSafe] path; keeps the cursor semantics for
     *   chained operations like `ArrowLeft` → type).
     * - `exec` — one `document.execCommand('insertText')` call for the WHOLE
     *   text after focusing and collapsing the cursor to the end.  Fast and
     *   reliable on contenteditable editors that accept `beforeinput`
     *   `insertText` transactions (X.com composer and many DraftJS/ProseMirror
     *   based editors), but produces NO keyboard event chain — sites that
     *   require key events (shortcuts, autocomplete) may not react to it.
     * - `auto` (default) — `exec` when the text is long (>150 chars) or
     *   multi-line AND the target can hold the text (textarea/contenteditable,
     *   or input without newlines — `<input>` drops newlines); otherwise
     *   `chars`.
     *
     * ## Verification
     * When [verify] is true the driver reads the element text back after the
     * insert (normalized: NBSP→space, CRLF→LF, trailing whitespace trimmed)
     * and compares it against `old + text`.  Any mismatch throws
     * [IllegalStateException] — typing never fails silently.  Verification is
     * off by default so existing callers keep their current behavior; the
     * `exec` path still throws immediately when the editor rejects the insert
     * (returned false / not editable) regardless of [verify].
     *
     * @throws IllegalArgumentException for an unknown method, an unresolvable
     *   target, a non-editable/disabled/read-only target, or an exec insert
     *   the editor rejected.
     * @throws IllegalStateException when [verify] is true and the read-back
     *   does not match.
     */
    @Throws(WebDriverException::class)
    suspend fun typeAuto(selector: String, text: String, method: String = "auto", verify: Boolean = false) {
        require(selector.isNotBlank()) { "typeAuto requires a selector (verification needs a read-back target)" }
        require(text.isNotEmpty()) { "typeAuto requires non-empty text" }
        val mode = method.lowercase()
        require(mode in TYPE_METHODS) { "type method must be one of ${TYPE_METHODS.joinToString("|")} (got '$method')" }

        // Probe the target once: kind / disabled / readOnly / current text.
        val probeResult = evaluateValue(selector, typeTargetProbeJs())
        val probe = probeResult as? Map<*, *>
        if (probe == null || probe["found"] != true) {
            throw IllegalArgumentException(
                "type: no element found for selector [$selector]. " +
                    "The selector may be stale — re-run `snapshot` to refresh refs."
            )
        }
        val kind = (probe["kind"] as? String) ?: "other"
        val disabled = probe["disabled"] == true
        val readOnly = probe["readOnly"] == true
        if (disabled || readOnly) {
            throw IllegalArgumentException(
                "type: target [$selector] is ${if (disabled) "disabled" else "read-only"} — user input is blocked."
            )
        }
        val oldText = (probe["text"] as? String) ?: ""

        val hasNewline = text.contains('\n') || text.contains('\r')
        val isLong = text.length > TYPE_EXEC_LONG_THRESHOLD
        // exec cannot deliver newlines into <input> (they are dropped silently).
        val execCapable = kind == "contenteditable" || kind == "textarea" || (kind == "input" && !hasNewline)
        val useExec = when {
            kind == "other" -> false
            mode == "exec" -> true
            mode == "auto" -> execCapable && (isLong || hasNewline)
            else -> false
        }

        if (useExec) {
            val execResult = evaluateValue(selector, typeExecJs(text))
            val exec = execResult as? Map<*, *>
            if (exec?.get("ok") != true) {
                val reason = (exec?.get("reason") as? String) ?: "editor rejected the bulk insert"
                throw IllegalArgumentException(
                    "type (method=$mode): bulk insert into [$selector] failed: $reason. " +
                        "The page may need per-character input — use method=chars (browser4-cli type --method chars) " +
                        "or paste the content manually."
                )
            }
        } else {
            // Per-code-point typing (also the fallback for non-editable-looking
            // targets like plain divs, preserving legacy behavior).
            typeSafe(text, selector)
        }

        if (verify) {
            verifyTypedText(selector, oldText, text)
        }
    }

    /**
     * Read back the element text after a type operation and compare it with
     * `old + text` (both normalized); throw on mismatch so typing never fails
     * silently.  See [typeAuto] for the normalization rules.
     */
    private suspend fun verifyTypedText(selector: String, oldText: String, typedText: String) {
        val newValue = evaluateValue(selector, typeReadBackJs())
        val newText = newValue as? String ?: ""
        val expected = (normalizeEditorText(oldText) + normalizeEditorText(typedText)).trimEnd()
        val actual = normalizeEditorText(newText).trimEnd()
        if (actual != expected) {
            throw IllegalStateException(
                "type: verification failed — the editor content does not match the typed text.\n" +
                    "  expected (suffix): ...${expected.takeLast(120)}\n" +
                    "  actual   (suffix): ...${actual.takeLast(120)}\n" +
                    "The page may have rewritten, truncated, or dropped characters. " +
                    "If the editor is a rich-text composer, retry with method=exec " +
                    "(browser4-cli type --method exec) or check the element constraints."
            )
        }
    }

    /**
     * Press a [key] on the element identified by [selector] — an alias of
     * [press] kept for backward compatibility with the original
     * Browser4WebDriver extension surface.
     */
    @Throws(WebDriverException::class)
    suspend fun pressSafe(key: String, selector: String) {
        press(key, selector)
    }

    /**
     * Fill the element identified by [selector] with [text], respecting the
     * element's user-input constraints:
     *
     * - `readonly` and `disabled` elements keep their current value (user
     *   input is blocked, and a programmatic assignment would silently
     *   bypass that constraint).
     * - `maxlength` is honored so a long string cannot silently overflow
     *   (browsers enforce it for user input but not for programmatic
     *   `value` assignment).
     * - number / range inputs use `valueAsNumber` to avoid string-coercion
     *   edge cases that can leave the value empty.
     * - contenteditable elements get their text content replaced.
     *
     * The element is resolved via [PulsarWebDriver.evaluateValue], which
     * supports CSS selectors, XPath, and `backend:nodeId` / `e123` locators
     * (unlike a raw `document.querySelector`), and evaluates with `this`
     * bound to the target element.
     */
    @Throws(WebDriverException::class)
    suspend fun fillSafe(selector: String, text: String) {
        evaluateValue(selector, fillValueJs(text))
    }

    // ---------------------------------------------------------------------------
    // Navigation settle — after a navigation-triggering action, detect whether
    // the page started navigating and wait for the DOM to settle.  Moved from
    // BrowserTabToolExecutor.waitForPotentialNavigation so every driver caller
    // (not just the tool layer) gets the same post-navigation wait.
    // ---------------------------------------------------------------------------

    /**
     * After a navigation-triggering action, detect whether the page started
     * navigating and wait for the DOM to settle before returning.
     *
     * Algorithm:
     * 1. Check if the URL already changed — if so the navigation is complete,
     *    wait for `body` + a short settle delay.
     * 2. If the URL is unchanged, eval `document.readyState`. If "loading", the
     *    navigation is in flight — poll readyState until 'complete' (bounded by
     *    [pollTimeoutMillis]).
     * 3. Otherwise no navigation occurred — return immediately (no unnecessary
     *    delay).
     *
     * Do NOT use [waitForNavigation] here: the no-arg overload's predicate is
     * `"" != currentUrl()`, true as soon as the page has any URL, so it returns
     * immediately without waiting; and the oldUrl overload can never complete
     * for a same-URL navigation (reload, same-URL goto, fragment/SPA
     * navigation that keeps the URL). Polling `document.readyState` covers both
     * URL-changing and same-URL navigations.
     *
     * @param urlBefore The URL observed before the action, used to detect a
     *   completed URL-changing navigation.
     * @param pollTimeoutMillis Upper bound for the readyState poll when the URL
     *   is unchanged and the document is still loading.
     */
    @Throws(WebDriverException::class)
    suspend fun waitForNavigationSettled(urlBefore: String, pollTimeoutMillis: Long = 30_000L) {
        // Wait for a while for the action effects
        delay(200)

        try {
            val urlAfter = currentUrl()
            if (urlAfter != urlBefore) {
                // URL already changed — navigation completed, wait for DOM to be ready.
                // Use a shorter timeout here since the navigation itself has already
                // finished; we only need the new page's body element to appear.
                waitForSelector("body", NAVIGATION_DOM_READY_TIMEOUT_MS)
                delay(NAVIGATION_DOM_SETTLE_DELAY_MS)
                return
            }

            // URL unchanged — check if the document is currently loading
            val readyState = evaluateValue("document.readyState") as? String
            if (readyState == "loading") {
                // Navigation is in flight. Do NOT use waitForNavigation(urlBefore, ...)
                // here: its predicate is `currentUrl() != urlBefore`, which can
                // never become true for a same-URL navigation (reload, same-URL
                // goto, fragment/SPA navigation that keeps the URL) — the wait
                // would burn the whole timeout silently. Poll document.readyState
                // instead, which covers both same-URL and URL-changing navigations.
                var sawComplete = false
                val deadline = System.currentTimeMillis() + pollTimeoutMillis
                while (System.currentTimeMillis() < deadline) {
                    val state = evaluateValue("document.readyState") as? String
                    if (state == "complete") {
                        sawComplete = true
                        break
                    }
                    delay(200)
                }

                // A URL change is expected only for link/form navigations; a
                // same-URL navigation (e.g. refresh) legitimately keeps the URL.
                if (sawComplete) {
                    // The document became ready — the navigation completed. The
                    // body should already exist; a short DOM-ready budget is
                    // enough (same as the URL-changing branch above). Do NOT
                    // use the full pollTimeoutMillis here: the poll already
                    // consumed that budget, and stacking another wait doubles
                    // the dead time when the body never appears.
                    waitForSelector("body", NAVIGATION_DOM_READY_TIMEOUT_MS)
                    delay(NAVIGATION_DOM_SETTLE_DELAY_MS)
                } else {
                    // The document never became ready — the navigation appears
                    // to have failed (e.g. the page context is wedged and evals
                    // return null). Do NOT pile waitForSelector("body", 30s) on
                    // top of the exhausted poll: the page is stuck, another
                    // full-timeout wait would double the dead time for every
                    // navigation-triggering action. Surface the warning and let
                    // the caller recover (reload, reopen the tab).
                    val finalUrl = currentUrl()
                    logger.warn(
                        "waitForNavigationSettled: document never became ready after the action " +
                            "(url='{}'). Navigation may have failed silently.",
                        finalUrl
                    )
                }
            } else {
                // No navigation detected. The action may have been a no-op (e.g. retry
                // computed a wrong targetIndex). Log at debug for diagnostics.
                logger.debug(
                    "waitForNavigationSettled: no navigation detected. " +
                        "urlBefore='{}', urlAfter='{}', readyState='{}'",
                    urlBefore,
                    urlAfter,
                    readyState
                )
            }
        } catch (e: Exception) {
            // Best-effort: navigation detection failures should not break the command
            logger.debug("waitForNavigationSettled: exception while checking navigation: {}", e.message)
        }
    }

    // ---------------------------------------------------------------------------
    // Enter-submit fallback — a CDP-dispatched Enter does not reliably trigger
    // the browser's implicit form submission (HTML §4.10.2.2), and JS-heavy
    // SPAs may intercept both.  submitFormFallback is the last-resort path used
    // by the tool layer after `press("Enter")` did not navigate.
    // ---------------------------------------------------------------------------

    /**
     * Submit the nearest form of the element matched by [selector] by dispatching
     * DOM keyboard events (keydown/keypress/keyup) and calling
     * `form.requestSubmit()` (with `form.submit()` fallback).  Used when a
     * CDP-dispatched Enter did not cause navigation — JS-heavy SPAs may intercept
     * both the trusted key event and the implicit form submission.
     *
     * @param selector A CSS selector for the filled element whose form is submitted.
     * @return false when the element is not found, true otherwise (form may or may
     *   not exist — the dispatch still runs).
     */
    @Throws(WebDriverException::class)
    suspend fun submitFormFallback(selector: String): Boolean {
        val result = evaluate(submitFormFallbackJs(selector))
        return result == true
    }

    // ---------------------------------------------------------------------------
    // Console message buffer — intercepts console.log/warn/error/info/debug on
    // first call and buffers subsequent messages on window.__b4_console.
    // Moved from BrowserTabToolExecutor so the buffer behavior is driver-owned
    // and reusable outside the tool layer.
    // ---------------------------------------------------------------------------

    /**
     * Read the buffered browser console messages filtered to [level] and above
     * (error=0, warn=1, info=2, log=2, debug=3).  Intercepts the console on
     * first call and buffers subsequent messages.
     */
    @Throws(WebDriverException::class)
    suspend fun consoleMessages(level: String = "info"): JsEvaluation? =
        evaluateValueDetail(consoleMessagesJs(level))

    /**
     * Clear the buffered browser console messages.
     */
    @Throws(WebDriverException::class)
    suspend fun consoleClear(): JsEvaluation? =
        evaluateValueDetail(consoleClearJs())

    // ---------------------------------------------------------------------------
    // Dual-world runtime recovery — PulsarWebDriver registers the Browser4
    // runtime (__pulsar_utils__) into a tab's isolated world when it navigates
    // or when the tab's main frame navigates (onFrameNavigated0).  A driver
    // bound to a tab *after* its document committed — a tab opened by tab-new
    // and then switched to, or any driver swap — has neither hook fired, so
    // its isolated-world context cache is empty (or points at a world that no
    // longer exists) and evaluations fall back to the main world, where
    // __pulsar_utils__ never exists.  Every helper that dereferences it
    // (capture meta links, document features, original-content-length, ...)
    // then throws a ReferenceError that breaks capture until the session is
    // closed.  This method (re-)establishes the runtime on demand.
    // ---------------------------------------------------------------------------

    /**
     * Ensure the Browser4 dual-world runtime (__pulsar_utils__) is available
     * to evaluations on this tab, re-registering it when the tab never
     * received it.
     *
     * The recovery mirrors [PulsarWebDriver.onFrameNavigated0]: stale cached
     * execution contexts are dropped, then the isolated world of the current
     * main frame is located or created.  `Page.createIsolatedWorld` is
     * idempotent per frame + world name — when the world already exists (a
     * previous driver instance registered it for this document), its context
     * id is returned and reused, and the runtime is injected only when the
     * world is fresh and empty, so an already-initialized world is never
     * re-run.
     *
     * @return true when `__pulsar_utils__` is available afterwards.
     */
    suspend fun ensurePulsarUtilsInjected(): Boolean {
        // typeof() does not dereference the variable, so a missing runtime
        // reports "undefined" instead of throwing a ReferenceError.  When the
        // cached isolated-world context is valid this probe resolves through
        // the isolated world; a driver with no cached context (e.g. freshly
        // bound to an already-loaded tab) falls back to the main world and
        // reports missing — the recovery below then locates or creates the
        // real isolated world and caches its context id.
        if (runCatching { evaluate("typeof($PULSAR_UTILS_FUNCTION)") }.getOrDefault(null) == "function") {
            return true
        }

        return runCatching {
            val runtimeJs = settings.dualWorldScriptLoader.getIsolatedWorldJs(false)
            if (runtimeJs.isBlank()) {
                return false
            }
            check(browserProtocol.isOpen) { "Underlying browser (BrowserProtocol) is closed" }

            val worldManager = page.isolatedWorldManager
            val mainFrameId = browserProtocol.getFrameTree().frame.id
            val contextId = worldManager.createIsolatedWorld(mainFrameId)
            val hasRuntime = browserProtocol.evaluate(
                "typeof($PULSAR_UTILS_FUNCTION)",
                contextId = contextId,
            )?.result?.value == "function"
            if (!hasRuntime) {
                worldManager.injectRuntime(runtimeJs, contextId)
            }

            val verified = browserProtocol.evaluate(
                "typeof($PULSAR_UTILS_FUNCTION)",
                contextId = contextId,
            )?.result?.value == "function"
            if (!verified) {
                logger.warn(
                    "Failed to inject the Browser4 runtime into the isolated world of tab {}",
                    guid
                )
            }
            verified
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------------------
    // Capture annotations — the annotated serializer behind [pageSource] /
    // [outerHTML] emits `vi` bounding boxes and the `normalizedURI` link only
    // once the runtime holds them, and silently degrades to plain `outerHTML`
    // otherwise.  Layout-dependent consumers (html snapshot bounding boxes,
    // X-SQL visual features) and offline consumers of a captured page (the page
    // URL of the artifact) therefore cannot rely on the serializer alone.  The
    // fetch/capture pipeline annotates the pages it captures itself;
    // [ensureViDataComputed] gives every other WebDriver-layer HTML read the same
    // guarantee on demand.
    // ---------------------------------------------------------------------------

    /**
     * Normalizes the URL of the live document for the `normalizedURI` capture
     * link, returning null when the URL has no normal form.
     *
     * Normalization is a session-scoped policy (`PulsarSession.normalize`), so
     * the session that binds this driver installs it here; a driver without one
     * cannot annotate captured HTML with a page URL and leaves the link alone.
     */
    @Volatile
    var pageUrlNormalizer: ((url: String) -> String?)? = null

    /**
     * The document URL of the last reported vi failure, so a page that cannot be
     * annotated does not log one warning per serialization.
     */
    @Volatile
    private var viFailureReportedForUrl: String? = null

    /**
     * Ensure the live document of this tab is annotated for serialization: its
     * visual-information (`vi`) data is computed when the document has none yet,
     * and the page URL used by offline consumers (`link[rel=normalizedURI]`) is
     * stored so both annotations reach the HTML together.
     *
     * `__pulsar_utils__.getAnnotatedHTML()` — the serializer behind [pageSource]
     * and [outerHTML] — returns plain `documentElement.outerHTML` while
     * `_viDataComputed` is false, and the boxes cannot be recovered afterwards:
     * the runtime keeps them in a WeakMap that only `compute()` fills.  The link
     * has the same shape: it is injected into the serialized `<head>` from
     * `_captureMetaLinks`, which only the fetch/capture pipeline used to store.
     * Without this call the HTML of a session that merely navigated (goto, tab
     * switch, form submission) carries no annotation at all, while the same page
     * fetched by the crawl pipeline carries both.
     *
     * Idempotent and cheap on an annotated document (one CDP evaluation): the
     * link is stored only when it is missing or describes another URL — a page
     * the fetch/capture pipeline already annotated keeps its value, and a
     * document whose URL changed since (a same-document navigation) is corrected
     * instead of shipped with a stale link.  On a tab without the runtime, or on
     * a document that cannot be annotated yet, it reports false instead of
     * failing the caller.
     *
     * Note that computing the features is not read-only: the runtime stores its
     * metadata elements in the document (`#PulsarMetaInformation`,
     * `#PulsarScriptSection`) and settles the page (`window.stop()`), exactly as
     * the fetch/capture pipeline already does for every page it captures.
     *
     * @param normalizedUri The page URL to record, already normalized by the
     * session; when null the driver resolves it from the live document URL
     * through [pageUrlNormalizer].
     * @return true when `vi` data is available for the current document.
     */
    @Throws(WebDriverException::class)
    suspend fun ensureViDataComputed(normalizedUri: String? = null): Boolean {
        var probe = probeViData()
        if (probe?.status == VI_DATA_UNAVAILABLE) {
            // The runtime is registered into the tab's isolated world when the
            // tab navigates.  A driver bound to an already-loaded tab (tab-new
            // then select, or a driver swap) has no cached context for it, so
            // neither the runtime nor its serializer exists until the world is
            // re-registered.
            ensurePulsarUtilsInjected()
            probe = probeViData()
        }

        if (probe == null) {
            logger.debug("The Browser4 runtime is unavailable on tab {}; its HTML carries no annotation", guid)
            return false
        }

        storeCaptureMetaLink(probe, normalizedUri)

        return when (probe.status) {
            VI_DATA_COMPUTED -> true

            VI_DATA_NOT_READY -> {
                logger.debug("Tab {} has no document body yet; its HTML carries no vi data", guid)
                false
            }

            VI_DATA_UNAVAILABLE -> {
                logger.debug("The Browser4 runtime is unavailable on tab {}; its HTML carries no vi data", guid)
                false
            }

            else -> {
                // A document that cannot be annotated fails on every read; warn
                // once per document so an unusable page does not turn every
                // serialization into a warning.
                if (shouldReportViFailure(viFailureReportedForUrl, probe.documentUrl)) {
                    viFailureReportedForUrl = probe.documentUrl
                    logger.warn(
                        "The Browser4 runtime did not produce visual information (vi) on tab {} for '{}'; " +
                            "the serialized HTML carries no bounding boxes",
                        guid, probe.documentUrl
                    )
                }
                false
            }
        }
    }

    /** Evaluate [viDataStatusJs]; an unreachable page reports no probe at all. */
    private suspend fun probeViData(): ViDataProbe? =
        runCatching { parseViDataProbe(evaluate(viDataStatusJs())) }.getOrNull()

    /**
     * Store the `normalizedURI` capture link for the document described by
     * [probe], so the serializer writes `<link rel="normalizedURI">` into the
     * serialized `<head>` next to the `vi` attributes.
     *
     * The caller's [explicitUri] wins; otherwise the live document URL is
     * normalized through [pageUrlNormalizer].  The link is stored only when it is
     * missing or describes a different URL, so the value the fetch/capture
     * pipeline already stored for this document is never rewritten with an
     * equivalent one — and a document whose URL changed since (a same-document
     * navigation) is corrected instead of shipped with a stale link.
     */
    private suspend fun storeCaptureMetaLink(probe: ViDataProbe, explicitUri: String?) {
        val uri = explicitUri?.takeIf { it.isNotBlank() }
            ?: run {
                val normalizer = pageUrlNormalizer ?: return
                runCatching { normalizer(probe.documentUrl) }
                    .onFailure { logger.debug("Failed to normalize '{}' on tab {}: {}", probe.documentUrl, guid, it.message) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return
            }

        if (uri == probe.storedUri) {
            return
        }

        runCatching { evaluate(storeCaptureMetaLinkJs(uri)) }
            .onFailure { logger.debug("Failed to store the normalized URI on tab {}: {}", guid, it.message) }
    }

    /**
     * Serialize the live document, ensuring its capture annotations (`vi`
     * bounding boxes and the `normalizedURI` link) are available first.
     *
     * The upstream serializer emits them only after `__pulsar_utils__.compute()`
     * has run and the capture meta links are stored, and falls back to plain
     * `outerHTML` otherwise — so the documented guarantee of the WebDriver layer
     * is enforced here instead of at every call site.  The guarantee is best
     * effort: a document without the runtime, or without a body, still
     * serializes — just without annotations.
     */
    @Throws(WebDriverException::class)
    override suspend fun pageSource(): String? {
        ensureViDataComputedQuietly()
        return super.pageSource()
    }

    /**
     * Serialize a subtree of the live document, ensuring its capture annotations
     * are available first — the same guarantee as [pageSource].
     */
    @Throws(WebDriverException::class)
    override suspend fun outerHTML(selector: String): String? {
        ensureViDataComputedQuietly()
        return super.outerHTML(selector)
    }

    /**
     * Best-effort [ensureViDataComputed] for the serialization paths: a page
     * that cannot carry `vi` data must still serialize.
     */
    private suspend fun ensureViDataComputedQuietly() {
        runCatching { ensureViDataComputed() }.onFailure {
            logger.debug("vi data unavailable before serializing HTML on tab {}: {}", guid, it.message)
        }
    }

    // ---------------------------------------------------------------------------
    // Viewport screenshot — capture a screenshot of the [n]-th viewport, scrolling
    // first so lazy-loaded content renders before capture.  Moved from
    // BrowserTabToolExecutor.screenshot(viewport=...) so the geometry logic is
    // driver-owned and reusable outside the tool layer.
    // ---------------------------------------------------------------------------

    /**
     * Capture a screenshot of the [viewportIndex]-th viewport (0-based, negative
     * scrolls up from the current position).  Scrolls to the target viewport so
     * lazy-loaded content renders before capture, then captures the viewport-sized
     * rect at the actual post-scroll position.
     *
     * @return The screenshot data (format matches the driver's screenshot()).
     */
    @Throws(WebDriverException::class)
    suspend fun screenshotViewport(viewportIndex: Double): String? {
        val w = evaluateValue("window.innerWidth")?.toString()?.toDoubleOrNull() ?: 1920.0
        val h = evaluateValue("window.innerHeight")?.toString()?.toDoubleOrNull() ?: 1080.0
        // Scroll to the target viewport (scroll-relative) so lazy-loaded content
        // renders before capture. Use the returned scrollY so the screenshot
        // rect matches the actual post-scroll position.
        val actualScrollY = scrollToViewport(viewportIndex)
        val rect = RectD(0.0, actualScrollY, w, h)
        return screenshot(rect)
    }

    // ---------------------------------------------------------------------------
    // Storage state — the upstream pulsar-browser implementation races the
    // per-origin navigation and can evaluate `window.localStorage` against an
    // opaque-origin provisional document (`SecurityError: Access is denied for
    // this document`).  `open()` delegates to `waitForNavigation()` with the
    // default `oldUrl=""`, which short-circuits as soon as the tab has *any*
    // URL, so the restore runs before the target document commits.  This
    // override navigates to each origin and waits until the document has
    // actually committed to that origin before restoring its localStorage.
    // ---------------------------------------------------------------------------

    /**
     * Loads a previously saved browser storage state JSON, restoring cookies
     * and localStorage.
     *
     * Overrides the upstream pulsar-browser implementation to wait for each
     * origin's document to commit before touching `window.localStorage`, which
     * is only accessible on a document with a standard (non-opaque) origin.
     *
     * @param state A JSON string produced by [saveStorageState].
     * @return A JSON summary of the restored cookies, origins, and localStorage entries.
     */
    @Throws(WebDriverException::class)
    override suspend fun loadStorageState(state: String): String {
        val payload = storageStateMapper.readValue<StorageStatePayload>(state)
        val cookies = payload.cookies.map(::normalizeStorageStateCookie)
        if (cookies.isNotEmpty()) {
            browserProtocol.setCookies(cookies)
        }

        val originalUrl = currentUrl()
        var restoredOrigins = 0
        var restoredLocalStorageEntries = 0

        payload.origins.forEach { originState ->
            val origin = originState.origin.trim()
            require(origin.isNotEmpty()) { "Storage state origin must not be blank" }
            require(URLUtils.isStandard(origin)) { "Storage state origin must be a standard URL: $origin" }

            navigate(origin)
            restoreLocalStorageForOrigin(origin, originState.localStorage)
            restoredOrigins += 1
            restoredLocalStorageEntries += originState.localStorage.size
        }

        if (payload.origins.isNotEmpty() && originalUrl.isNotBlank() && currentUrl() != originalUrl) {
            open(originalUrl)
        }

        return storageStateMapper.writeValueAsString(
            StorageStateLoadSummary(
                cookies = cookies.size,
                origins = restoredOrigins,
                localStorageEntries = restoredLocalStorageEntries,
            )
        )
    }

    /**
     * Saves the browser's cookies plus the active origin's localStorage as the
     * storage-state JSON consumed by [loadStorageState].
     *
     * Overrides the upstream pulsar-browser implementation, which reads cookies
     * through the typed CDP layer: it casts every element of the
     * `Network.getAllCookies` result to
     * `ai.platon.cdt.kt.protocol.types.network.Cookie`.  Over the extension
     * relay that response arrives as generic JSON maps, so the cast throws
     * `ClassCastException: LinkedHashMap cannot be cast to Cookie` and every
     * cookie-reading tool (`state-save`, `cookie-list`, `cookie-get`) fails on
     * `attach --extension` sessions — the documented "reuse your logged-in
     * browser" path.  Reading the raw result and normalizing the fields here
     * works over both transports.
     *
     * @return A JSON storage-state payload:
     *   `{"cookies": [...], "origins": [{"origin": "...", "localStorage": [...]}]}`.
     */
    @Throws(WebDriverException::class)
    override suspend fun saveStorageState(): String {
        val payload = linkedMapOf<String, Any?>(
            "cookies" to readAllCookiesViaCdp(),
            "origins" to captureCurrentOriginLocalStorage(),
        )
        return storageStateMapper.writeValueAsString(payload)
    }

    /**
     * Every cookie in the browser cookie jar, as `name -> value` maps.
     *
     * Uses the same raw-CDP read as [saveStorageState] — see there for why the
     * typed upstream implementation cannot be used on extension-attached
     * sessions.
     */
    @Throws(WebDriverException::class)
    override suspend fun getCookies(): List<Map<String, String>> =
        readAllCookiesViaCdp().map { cookie ->
            cookie.entries.associate { (key, value) -> key to (value?.toString() ?: "") }
        }

    /**
     * Read the whole cookie jar through `Network.getAllCookies` and normalize
     * every entry into the canonical storage-state field set
     * ([normalizeStorageStateCookie]).
     */
    private suspend fun readAllCookiesViaCdp(): List<Map<String, Any?>> {
        val raw = executeCdpCommand("Network.getAllCookies", emptyMap())
        return extractCookiesFromCdpResult(raw).map(::normalizeStorageStateCookie)
    }

    /**
     * Capture the active origin and its localStorage entries as the `origins`
     * section of the storage-state payload.
     *
     * Only the origin of the document that is currently open is captured:
     * localStorage is origin-scoped and the browser exposes no API to enumerate
     * every origin's store.  Returns an empty list when the active document has
     * no standard origin (e.g. `about:blank`).
     */
    private suspend fun captureCurrentOriginLocalStorage(): List<Map<String, Any?>> {
        val origin = runCatching { evaluateValue("location.origin")?.toString()?.trim() }.getOrNull()
        if (origin.isNullOrEmpty() || !URLUtils.isStandard(origin)) {
            return emptyList()
        }

        val json = runCatching { evaluateValue(captureLocalStorageScript())?.toString() }.getOrNull()
        if (json.isNullOrBlank()) {
            return emptyList()
        }

        val entries: Map<String, String> = storageStateMapper.readValue(json)
        val localStorage = entries.map { (name, value) -> mapOf("name" to name, "value" to value) }
        return listOf(mapOf("origin" to origin, "localStorage" to localStorage))
    }

    /**
     * Navigate to [origin] and restore its localStorage entries once the main
     * document has actually committed to that origin.  Retries transient
     * evaluation failures (execution contexts are destroyed/recreated while a
     * navigation commits) and fails loudly if the document never settles.
     */
    private suspend fun restoreLocalStorageForOrigin(
        origin: String,
        entries: List<StorageStateEntryPayload>,
    ) {
        val normalizedEntries = entries.map { entry ->
            val name = entry.name.trim()
            require(name.isNotEmpty()) { "localStorage entry name must not be blank" }
            mapOf(
                "name" to name,
                "value" to entry.value,
            )
        }
        val entriesJson = storageStateMapper.writeValueAsString(normalizedEntries)
        val script = restoreLocalStorageScript(entriesJson)
        val waitTimeout = timeout("waitForNavigation")
        val deadline = System.nanoTime() + waitTimeout.toMillis() * 1_000_000L

        while (System.nanoTime() < deadline) {
            val currentOrigin = runCatching { evaluateValue("location.origin")?.toString()?.trim() }
                .getOrNull()
            if (isDocumentOriginReady(currentOrigin, origin)) {
                // The document is committed; restore now.  A thrown
                // evaluation error here means the context was destroyed
                // between the probe and the write — keep polling.
                val restoredCount = runCatching { evaluateValue(script) }.getOrNull()
                if (restoredCount != null) {
                    val count = (restoredCount as? Number)?.toInt()
                    require(count == normalizedEntries.size) {
                        "Expected to restore ${normalizedEntries.size} localStorage entries but restored ${count ?: "none"}"
                    }
                    return
                }
            }
            delay(200)
        }

        logger.warn(
            "Timed out restoring localStorage for origin {} after {}",
            origin,
            waitTimeout
        )
        throw WebDriverException(
            "Timed out restoring localStorage for origin $origin after $waitTimeout"
        )
    }

    /**
     * Re-implementation of the parent's private `trySubmitFormOnEnter()`.
     *
     * CDP `Input.dispatchKeyEvent` sends trusted keydown/keypress events, but
     * Chromium does not reliably fire the implicit form submission default
     * action (HTML spec §4.10.2.2) for synthesized input.  This method is a
     * safety net: after a CDP `Enter` lands, it explicitly submits the nearest
     * eligible form via `requestSubmit()` (with `submit()` fallback).
     *
     * Elements excluded (Enter does *not* implicitly submit for these):
     * - `<textarea>` — Enter inserts a newline
     * - `<input type="radio|checkbox|file|button|reset|submit|image|hidden">`
     * - Any element not inside a `<form>`
     */
    private suspend fun trySubmitFormOnEnter() {
        runCatching {
            browserProtocol.evaluate(
                expression = PulsarWebDriver.TRY_SUBMIT_FORM_ON_ENTER_JS,
                returnByValue = true,
            )
        }.onFailure {
            // Best-effort safety net — a failure here must not fail the press itself.
        }
    }

    // ---------------------------------------------------------------------------
    // Drag & drop fix — the upstream pulsar-browser:4.11.x drag() dispatches
    // synthetic (untrusted) DragEvents from JS, which never reach listeners
    // registered by the page's own scripts (isolated-world dispatch does not
    // cross into the main world for drag events).  This override runs the same
    // event sequence through callFunctionOn in the main world (no isolated-world
    // context id), so page-registered listeners receive the full drag lifecycle
    // (dragstart → dragenter → dragover → drop → dragend).
    //
    // Hardening over the initial fix:
    // - The source element is bound as `this` (a real CDP node reference), so
    //   the dragstart/dragend always fire on the intended node.
    // - The target is re-located by a stable CSS path and must still be hit by
    //   its resolved viewport point (elementFromPoint).  Occluded targets,
    //   pointer-events:none targets, and targets moved by an async layout shift
    //   fail loudly instead of silently dispatching on an unrelated element.
    // - Elements inside frames are rejected explicitly: their coordinates are
    //   frame-relative and elementFromPoint runs in the top document, so a
    //   frame drag would silently target the wrong element.
    // - Press/release points are jittered and inter-event delays randomized,
    //   so the synthetic sequence does not fingerprint as a constant-pattern
    //   automation (see dragAndDrop for the same anti-detection intent).
    // - All failure paths return before any event is dispatched, keeping the
    //   outer RPC retry idempotent.
    //
    // Known limitation (unchanged): the events remain synthetic
    // (isTrusted=false); libraries that gate on isTrusted (SortableJS,
    // react-dnd) still won't respond — a browser-level limitation.
    // ---------------------------------------------------------------------------

    /**
     * Drag the element identified by [sourceSelector] onto the element identified
     * by [targetSelector].
     *
     * Upstream drag() dispatches synthetic DragEvents through `JsHandler`,
     * which evaluates in an **isolated world** — the events never reach
     * main-world page listeners, so the drag silently does nothing.
     *
     * This override runs the same event sequence through
     * [BrowserProtocol.callFunctionOn] with the source bound as the call
     * receiver (main world, no isolated-world context id), so page-registered
     * listeners receive the full drag lifecycle
     * (dragstart → dragenter → dragover → drop → dragend), verified against a
     * live listener probe.
     *
     * Notes on what was tried and ruled out:
     * - CDP `Input.dispatchDragEvent` + manual DragData: accepted by Chrome,
     *   but libraries require `dragstart`, which CDP never emits.
     * - Trusted CDP mouse sequences (press → move → release): headless Chrome
     *   never starts the native drag state machine, so no dragstart fires.
     * - Synthetic events are `isTrusted=false`; libraries that gate on
     *   isTrusted (SortableJS, react-dnd) will not respond.  That is a
     *   browser-level limitation, not fixable from the driver.
     *
     * @param sourceSelector A CSS selector, XPath, or "backend:nodeId" locator for the drag source.
     * @param targetSelector A CSS selector, XPath, or "backend:nodeId" locator for the drop target.
     * @throws WebDriverException if either element cannot be located, lives in a
     *   frame, is occluded/moved at drag time, or the script fails.
     */
    @Throws(WebDriverException::class)
    override suspend fun drag(sourceSelector: String, targetSelector: String): Unit {
        dragAt(sourceSelector, targetSelector, DragDropPosition.CENTER.key)
    }

    /**
     * Drag and drop [sourceSelector] onto [targetSelector] with an explicit
     * drop [position] on the target, returning a short summary of where the
     * source element ended up (used by the CLI `drag --at` flow).
     *
     * The interface-level [drag] (two-argument) stays a `Unit` override for
     * upstream callers; tool callers that want the placement summary (and
     * deterministic positioning) use this overload.
     *
     * @param position One of `"center"` (legacy: resolved center point with
     *        ±2px jitter), `"top"` (drop at the target's top edge region —
     *        insert *before* the target on child-index-based reorder lists),
     *        or `"bottom"` (drop at the target's bottom edge region — insert
     *        *after* the target).  Top/bottom points are read from the live
     *        rect inside the page at dispatch time, so they cannot drift
     *        outside the target and carry no jitter.
     * @return A human-readable summary of the source's resulting DOM position,
     *         e.g. `Dropped li#priorityHigh as child 4 of 4 in ul#priorityList`.
     * @throws IllegalArgumentException if [position] is not a known value.
     * @throws WebDriverException if the source or target cannot be located or the drag fails.
     */
    @Throws(WebDriverException::class)
    suspend fun drag(sourceSelector: String, targetSelector: String, position: String): String {
        val normalized = DragDropPosition.from(position).key
        return dragAt(sourceSelector, targetSelector, normalized)
    }

    private suspend fun dragAt(
        sourceSelector: String,
        targetSelector: String,
        position: String,
    ): String {
        // Phase 1 — resolve (retryable pieces are covered by their own RPC
        // layers; deterministic failures like a missing element must surface
        // directly instead of being wrapped by the outer retry machinery).
        // Only the target is scrolled into view: the source is bound as a real
        // CDP node, so it needs no viewport presence.  PageHandler's
        // scrollIntoViewIfNeeded prefers a *smooth* JS scroll (animated), whose
        // geometry stays in transit for hundreds of ms — unusable for
        // point-based validation — so use an instant scroll here instead.
        runCatching {
            evaluateValue(
                targetSelector,
                "function(){ this.scrollIntoView({ block: 'center', behavior: 'instant' }); return true; }",
            )
        }

        val source = resolveDragCenter(sourceSelector)
            ?: throw WebDriverException("Source element was not found: $sourceSelector", driver = this@Browser4WebDriver)
        // The target must be *visible*: even an instant scroll commits
        // asynchronously on the renderer, so poll until the resolved center
        // lands inside the viewport (or give up and let the script report the
        // real failure).  Re-scrolling inside the poll would restart any
        // in-flight scroll animation, so the poll only waits and re-reads.
        val target = resolveDragTargetInViewport(targetSelector)
            ?: throw WebDriverException("Target element was not found: $targetSelector", driver = this@Browser4WebDriver)

        if (source.inFrame || target.inFrame) {
            throw WebDriverException(
                "Drag into/from elements inside frames is not supported: '$sourceSelector' -> '$targetSelector'",
                driver = this@Browser4WebDriver
            )
        }

        val sourceNode = rpc.invokeOnPage("drag") { page.dom.queryLocator(sourceSelector) }
            ?: throw WebDriverException("Source element was not found: $sourceSelector", driver = this@Browser4WebDriver)

        // Humanize the sequence: jitter the press/release points (±2px) and
        // randomize inter-event delays (120-300ms, the same magnitude as the
        // type() bucket).  Constant centers and fixed delays are a fingerprint
        // for synthetic drags.  Positioned drops (top/bottom) pin their own
        // point from the live rect inside the page script, so no jitter is
        // applied here — ±2px around a center line is exactly what makes
        // insert-before/insert-after a coin flip on child-index-based reorder
        // lists, and a jittered point could drift outside the edge region.
        val positioned = position == DragDropPosition.TOP.key || position == DragDropPosition.BOTTOM.key
        val sourcePoint = Pair(source.x + randomOffset(2.0), source.y + randomOffset(2.0))
        val targetPoint = if (positioned) Pair(target.x, target.y) else Pair(target.x + randomOffset(2.0), target.y + randomOffset(2.0))
        // Positioned drops add two intermediate dragover sweep events, so they
        // pace six inter-event delays instead of four.
        val delays = List(if (positioned) 6 else 4) { randomDragDelayMillis() }

        val script = buildDragSequenceScript(
            targetCssPath = target.cssPath,
            sourceX = sourcePoint.first,
            sourceY = sourcePoint.second,
            targetX = targetPoint.first,
            targetY = targetPoint.second,
            delays = delays,
            dropPosition = position,
        )

        // Phase 2 — execute the sequence.  The source element is bound as
        // `this` (a real CDP node reference) and userGesture=true keeps
        // user-activation-gated APIs available to page dragstart listeners.
        // Page-side failures (occlusion, missing target) are deterministic:
        // they must propagate immediately with their real message.  Only
        // transient CDP failures are retried, manually, inside this block.
        withNodeObjectId(browserProtocol, sourceNode) { sourceObjectId ->
            var lastCdpFailure: ChromeDriverException? = null
            // DOM-position fingerprint of the source before any dispatch.
            // An earlier attempt can dispatch the full lifecycle and then die
            // with a *transient* CDP error; the retry's script pre-check then
            // reports the target as occluded/moved only because the page
            // already reordered.  When that happens, verify by measuring:
            // if the source demonstrably moved, the drag took effect and the
            // failure is a false negative — report success instead of throwing.
            val positionBefore = sourcePositionFingerprint(sourceObjectId)
            var dragSucceeded = false
            repeat(3) { attempt ->
                try {
                    val result = browserProtocol.callFunctionOn(
                        script,
                        objectId = sourceObjectId,
                        returnByValue = true,
                        userGesture = true,
                        awaitPromise = true,
                    )
                    val scriptError = dragScriptErrorMessage(result?.result?.value)
                    if (scriptError == null) {
                        dragSucceeded = true
                        return@repeat
                    }
                    if (attempt > 0 && positionBefore != null) {
                        val positionAfter = sourcePositionFingerprint(sourceObjectId)
                        if (positionAfter != null && positionAfter != positionBefore) {
                            logger.warn(
                                "Drag of '$sourceSelector' to '$targetSelector' failed its retry pre-check " +
                                    "($scriptError) but the source element already moved in the DOM — an earlier " +
                                    "attempt dispatched the drag lifecycle. Treating the drag as completed."
                            )
                            dragSucceeded = true
                            return@repeat
                        }
                    }
                    throw WebDriverException(
                        "Failed to drag '$sourceSelector' to '$targetSelector': $scriptError",
                        driver = this@Browser4WebDriver
                    )
                } catch (e: WebDriverException) {
                    throw e
                } catch (e: ChromeDriverException) {
                    lastCdpFailure = e
                    if (attempt < 2) {
                        delay(200)
                    }
                }
            }
            if (!dragSucceeded) {
                lastCdpFailure?.let { throw it }
            }
        }

        // Report where the source ended up so callers can verify the placement
        // (deterministic intent) instead of trusting the drop silently.
        val summary = elementPositionReport(sourceSelector)
        gap("drag")
        return summary
    }

    /**
     * Read the current DOM position of the drag source (by CDP object id) as a
     * comparable fingerprint string: `{ok, parentKey, index, total}`.  Returns
     * null when the read fails (transient CDP error) — callers treat null as
     * "cannot verify" and keep the original error instead of guessing.
     */
    private suspend fun sourcePositionFingerprint(sourceObjectId: String): String? =
        try {
            val result = browserProtocol.callFunctionOn(
                dragPositionFingerprintJs(),
                objectId = sourceObjectId,
                returnByValue = true,
            )
            result?.result?.value as? String
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Failed to read the drag source position fingerprint: ${e.message}")
            null
        }

    /**
     * Describe where the element matched by [selector] currently sits in the
     * DOM (parent element and child index).  Used after a drag to report the
     * resulting placement to the caller.  Falls back to a notice when the
     * element can no longer be located (a drop target may have consumed it).
     */
    internal suspend fun elementPositionReport(selector: String): String {
        val value = evaluateValue(selector, dragPositionReportJs())
        return parseDragPositionReport(value)
            ?: "The source element is no longer in the document after the drag; the drop target may have consumed it."
    }

    /**
     * Resolve the viewport center, stable CSS path, and frame residency of
     * [selector] via the driver's locator path.  Supports CSS selectors, XPath,
     * `backend:nodeId` and `eN` snapshot refs (the upstream evaluateValue
     * locator resolution), so drag works with every locator format the rest of
     * the CLI accepts. Returns null when the element does not exist or cannot
     * be re-located by CSS path.
     */
    private suspend fun resolveDragCenter(selector: String): DragCenter? {
        val value = evaluateValue(selector, dragCenterJs())
        return parseDragCenter(value)
    }

    /**
     * Resolve [selector] like [resolveDragCenter], but keep polling until the
     * element's center is inside the viewport (scroll commits and smooth-scroll
     * animations are asynchronous, so a single read can observe stale
     * geometry).  Never re-scrolls inside the poll — that would restart any
     * in-flight scroll animation.  Returns the last resolution when the
     * element never becomes visible; the drag script then reports the real
     * failure (e.g. occluded) instead of this helper guessing.
     */
    private suspend fun resolveDragTargetInViewport(selector: String): DragCenter? {
        var last: DragCenter? = null
        repeat(15) {
            last = resolveDragCenter(selector) ?: return null
            val visible = last.viewportWidth <= 0 || (
                last.x >= 0 &&
                    last.y >= 0 &&
                    last.x <= last.viewportWidth &&
                    last.y <= last.viewportHeight
                )
            if (visible) {
                return last
            }
            delay(150)
        }
        return last
    }

    /** Uniform random offset in [-range, range], used to jitter drag points. */
    private fun randomOffset(range: Double): Double = Random.nextDouble(-range, range)

    /** Randomized inter-event delay for the drag sequence, 120-300 ms. */
    private fun randomDragDelayMillis(): Long = Random.nextLong(120L, 301L)

    // ---------------------------------------------------------------------------
    // upload fix — the upstream pulsar-browser upload
    // (1) silently reports success when the selector matches no element
    //     (RobustRPC.invokeOnElement skips the block for a null node),
    // (2) fails with an opaque CDP -32000 error when the target is not a
    //     file input or a path is not readable by the browser process, and
    // (3) sets files through the session-scoped `nodeId`, which is unstable
    //     across CDP sessions.
    //
    // This override keeps the whole chain — element resolution, DOM.describeNode
    // and DOM.setFileInputFiles — inside ONE RobustRPC call (same CDP session),
    // switches to the stable backendNodeId (agent-browser style), validates the
    // target is really an `<input type="file">`, and turns every silent or
    // opaque failure into an explicit, actionable error.
    // ---------------------------------------------------------------------------

    /**
     * Upload [paths] to the file input identified by [selector], failing loudly
     * (never silently) on every error path:
     *
     * - no element matches [selector] → `IllegalArgumentException` with a
     *   stale-ref hint;
     * - the target is not an `<input type="file">` → `IllegalArgumentException`;
     * - `DOM.setFileInputFiles` is rejected by Chromium (unreadable/relative
     *   path, wrong host topology) → `WebDriverException` wrapping the original
     *   CDP error with deployment guidance.
     *
     * The file paths are read by the **browser process** — in local mode that is
     * this machine (the CLI canonicalizes paths before dispatch); with a remote
     * backend the paths must exist on the backend/browser host.
     *
     * @param selector A CSS selector, XPath, or "backend:nodeId" locator for the file input.
     * @param paths Absolute paths of the files to upload (one or more).
     * @throws IllegalArgumentException on empty paths, a missing target, or a non-file-input target.
     * @throws WebDriverException when the CDP upload fails.
     */
    @Throws(WebDriverException::class)
    override suspend fun upload(selector: String, paths: List<String>) {
        if (paths.isEmpty() || paths.any { it.isBlank() }) {
            throw IllegalArgumentException(
                "upload requires at least one non-empty file path (got ${paths.size} path(s))"
            )
        }

        // All CDP steps run inside one RobustRPC attempt so the resolved DOM
        // node ids stay valid for the whole chain.
        val uploaded = rpc.invokeOnElement(selector, "upload", focus = true) { nodeRef ->
            if (!nodeRef.mayExist()) {
                // WebDriverException (not IllegalStateException): exceptions
                // thrown inside the RobustRPC block must be of a type RobustRPC
                // rethrows verbatim (ChromeDriverException/WebDriverException),
                // otherwise they are wrapped into a generic "Unexpected error in
                // [upload]" that hides the real cause from the user.
                throw WebDriverException(
                    "upload: could not resolve element for selector [$selector]",
                    driver = this
                )
            }

            val node = browserProtocol.describeNode(
                nodeId = nodeRef.nodeId.takeIf { it > 0 },
                backendNodeId = nodeRef.backendNodeId.takeIf { it > 0 },
                objectId = nodeRef.objectId,
            )

            // nodeName is uppercase for HTML elements ("INPUT"); missing
            // attributes means an <input> without an explicit type — which is
            // type=text, i.e. NOT a file input.
            val nodeName = node.nodeName?.uppercase()
            if (nodeName != "INPUT") {
                throw WebDriverException(
                    "upload: the target [$selector] is a <${nodeName ?: "unknown"}>, not a file input. " +
                        "Upload targets must be <input type=\"file\"> elements.",
                    driver = this
                )
            }
            val attributes = node.attributes.orEmpty()
            val isFileType = attributes
                .chunked(2)
                .any { (name, value) ->
                    name.equals("type", ignoreCase = true) && value.equals("file", ignoreCase = true)
                }
            if (!isFileType) {
                throw WebDriverException(
                    "upload: the target [$selector] is an <input> without type=\"file\" — " +
                        "only file inputs accept uploads.",
                    driver = this
                )
            }
            val backendNodeId = node.backendNodeId
            if (backendNodeId == null || backendNodeId <= 0) {
                throw WebDriverException(
                    "upload: DOM.describeNode returned no backendNodeId for selector [$selector]",
                    driver = this
                )
            }

            // The typed BrowserProtocol.setFileInputFiles only accepts the
            // session-scoped nodeId; the generic CDP channel accepts the
            // stable backendNodeId (browser_protocol.json:8358-8363) and works
            // over both the direct and the extension-relay transports.
            try {
                browserProtocol.executeCdpCommand(
                    "DOM.setFileInputFiles",
                    mapOf("files" to paths, "backendNodeId" to backendNodeId)
                )
            } catch (e: Exception) {
                throw WebDriverException(
                    "upload to [$selector] failed. DOM.setFileInputFiles requires absolute paths " +
                        "that are readable by the browser process (the machine hosting the backend). " +
                        "Original error: ${e.message}",
                    e,
                    driver = this
                )
            }
            true
        }

        if (uploaded != true) {
            throw IllegalArgumentException(
                "upload: no element found for selector [$selector]. " +
                    "The selector may be stale — re-run `snapshot` to refresh refs."
            )
        }
    }

    // ---------------------------------------------------------------------------
    // selectOption fix — the upstream pulsar-browser selectOption reports
    // success even when no element matches, which silently swallows typos and
    // stale refs.  This override probes the target first (via the driver's own
    // locator path, so CSS/XPath/backend:nodeId/eN all work) and fails loudly
    // before delegating.  Only the missing-target case is treated as "not
    // found" — driver, session and transport failures keep propagating (they
    // throw inside evaluateValue rather than returning null).
    // ---------------------------------------------------------------------------

    /**
     * Select [values] in the option element identified by [selector], failing
     * loudly when the target does not exist (the upstream implementation
     * reports success even for a missing element, silently swallowing typos
     * and stale refs).
     *
     * @param selector A CSS selector, XPath, or "backend:nodeId" locator for the select element.
     * @param values The option values to select.
     * @return The selected option values.
     * @throws WebDriverException if the element cannot be located or the selection fails.
     */
    @Throws(WebDriverException::class)
    override suspend fun selectOption(selector: String, values: List<String>): List<String> {
        val exists = evaluateValue(selector, "function(){ return this != null; }")
        selectOptionTargetError(selector, exists)?.let { throw IllegalArgumentException(it) }
        return super.selectOption(selector, values)
    }

    // ---------------------------------------------------------------------------
    // Dialog state fix — upstream dialogAccept/dialogDismiss call CDP
    // Page.handleJavaScriptDialog directly but never drain DialogHandler's
    // pending queue, and DialogHandler.onDialogClosed only logs.  The
    // tool-layer "blocked by a native dialog" guard checks that queue, so a
    // handled dialog keeps failing screenshots/health-checks until the session
    // is closed. These overrides acknowledge the queue head after CDP succeeds.
    // ---------------------------------------------------------------------------

    /**
     * Fail loudly when a native JavaScript dialog (alert/confirm/prompt) is
     * blocking the page.  Read-state operations that require JS execution via
     * CDP (ariaSnapshot, evaluate, select*, …) queue behind an open dialog and
     * never complete; this guard surfaces a clear error so the caller knows to
     * accept or dismiss the dialog first.
     *
     * @throws IllegalStateException when a dialog is pending.
     */
    fun requireNoPendingDialog() {
        val dialog = dialogHandler.peekPendingDialog() ?: return
        val type = dialog.type
        val message = if (dialog.message.length > 80) dialog.message.take(80) + "..." else dialog.message
        throw IllegalStateException(
            "Page is blocked by a native $type dialog${if (message.isNotEmpty()) ": \"$message\"" else ""}. " +
                "Use dialog-accept or dialog-dismiss to handle the dialog before reading page state."
        )
    }

    /**
     * Accept the current JavaScript dialog, then acknowledge exactly the dialog
     * CDP handled (queue head) so later pending entries stay intact.
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogAccept(promptText: String?): Unit {
        super.dialogAccept(promptText)
        acknowledgeHandledDialog()
    }

    /**
     * Dismiss (Cancel) the current JavaScript dialog, then acknowledge exactly
     * the dialog CDP handled (see [dialogAccept]).
     */
    @Throws(WebDriverException::class)
    override suspend fun dialogDismiss(): Unit {
        super.dialogDismiss()
        acknowledgeHandledDialog()
    }

    /**
     * Remove only the head of [DialogHandler]'s pending queue — the dialog CDP
     * just handled.  Unlike draining the whole queue, later entries (dialogs
     * queued after this one) are preserved.  The queue is only appended by
     * `Page.javascriptDialogOpening` and never emptied by the upstream dialog
     * path, so it must be acknowledged explicitly after a CDP
     * `Page.handleJavaScriptDialog` call.
     */
    private fun acknowledgeHandledDialog() {
        val acknowledged = dialogHandler.getPendingDialog()
        if (acknowledged != null) {
            logger.debug(
                "Acknowledged dialog handled by CDP: type={} message={}",
                acknowledged.type,
                acknowledged.message,
            )
        } else {
            // The opening event may still be in flight over the WebSocket, or
            // the dialog was opened before DialogHandler subscribed.  Nothing
            // to remove — the stale-entry risk this fix guards against does
            // not apply to a queue that is already empty.
            logger.debug("No pending dialog event to acknowledge after CDP dialog handling")
        }
    }
}
