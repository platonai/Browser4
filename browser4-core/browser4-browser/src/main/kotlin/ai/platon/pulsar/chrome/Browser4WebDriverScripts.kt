package ai.platon.pulsar.chrome

import ai.platon.pulsar.chrome.protocol.Keyboard
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * JavaScript snippets and escaping helpers used by [Browser4WebDriver].
 *
 * Every function here is a pure string builder: it takes the values to embed and returns the
 * script to evaluate. Keeping them out of the driver class keeps the CDP orchestration in
 * [Browser4WebDriver] readable, and lets the scripts be unit tested without a browser.
 */

internal object Browser4WebDriverScripts {

/**
 * Escape [text] for embedding as a single-quoted JavaScript string literal.
 * Escapes backslash, single quote, newline, and carriage return.
 */
internal fun escapeJsString(text: String): String =
    text.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

/**
 * Escape [selector] for embedding inside a single-quoted JavaScript string
 * literal (e.g. `document.querySelector('<selector>')`).  Escapes backslash
 * and single quote.
 */
internal fun escapeJsSelector(selector: String): String =
    selector.replace("\\", "\\\\").replace("'", "\\'")

/**
 * Split [text] into complete Unicode code points, preserving surrogate pairs
 * (emoji, CJK supplementary ideographs).  The upstream `Keyboard.type()` walks
 * the string with `charAt()` (UTF-16 code units), which splits surrogate pairs
 * into invalid halves.  Iterating the returned list inserts each complete code
 * point in a single CDP `Input.insertText` call.
 */
internal fun codePoints(text: String): List<String> {
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
internal fun fillValueJs(text: String): String =
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
 * Probe used by [Browser4WebDriver.typeAuto] (to choose an insertion
 * strategy) and by [Browser4WebDriver.fillSafe] (to refuse a target that
 * cannot be written) before either touches the element.  Evaluated with
 * `this` bound to the target element; returns `{found:false}` when the
 * locator resolves to nothing, otherwise the element kind,
 * disabled/readOnly flags, and its CURRENT text (for the verify
 * read-back).
 */
internal fun inputTargetProbeJs(): String =
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
internal fun typeExecJs(text: String): String =
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
internal fun typeReadBackJs(): String =
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
internal fun normalizeEditorText(text: String): String =
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
internal fun submitFormFallbackJs(selector: String): String =
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
 * The IIFE the executor uses for drivers that are not a [Browser4WebDriver], and the
 * fallback when the Console domain cannot be enabled ([consoleMessages]).
 *
 * It patches the page's `console.*` and buffers on `window.__b4_console`, which a page can
 * detect (the wrapper's source via `console.log.toString()` /
 * `Function.prototype.toString.call`, its `name`, and the `prototype` property a native
 * console method does not have) — [Browser4WebDriver.consoleMessages] therefore prefers CDP
 * capture and leaves this builder for the cases where CDP is unavailable.
 */
internal fun consoleMessagesJs(level: String): String =
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
internal fun consoleClearJs(): String = "window.__b4_console = []; 'Console cleared'"

/**
 * Build the JavaScript used to restore localStorage entries for a single
 * origin.  [entriesJson] must be a JSON array of `{name, value}` objects.
 */
internal fun restoreLocalStorageScript(entriesJson: String): String =
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
internal fun captureLocalStorageScript(): String =
    "JSON.stringify(Object.fromEntries(Object.entries(window.localStorage)))"

/**
 * Install a one-shot page probe that counts **trusted** mouse events.
 *
 * A CDP press/release pair either reaches the page — then the document observes trusted
 * `mousedown`/`mouseup`/`click` events — or it does not, and the driver must not report
 * success for a click that never happened. The probe is the authority for that decision. It
 * is installed as a non-enumerable global so a page enumerating `window` cannot see it, and
 * removed again by [trustedClickProbeReadJs].
 */
internal fun trustedClickProbeInstallJs(): String = """
    (function() {
        var state = { trusted: 0, listeners: [] };
        Object.defineProperty(window, '${Browser4WebDriver.TRUSTED_CLICK_PROBE}', {
            value: state, writable: true, configurable: true, enumerable: false
        });
        ['mousedown', 'mouseup', 'click'].forEach(function(name) {
            var listener = function(event) { if (event.isTrusted) { state.trusted += 1; } };
            document.addEventListener(name, listener, true);
            state.listeners.push([name, listener]);
        });
        return 'installed';
    })()
""".trimIndent()

/**
 * Remove the probe installed by [trustedClickProbeInstallJs] and report what it saw:
 * `"trusted"`, `"none"`, or `"missing"` when the global was gone (a page replaced it).
 */
internal fun trustedClickProbeReadJs(): String = """
    (function() {
        var state = window['${Browser4WebDriver.TRUSTED_CLICK_PROBE}'];
        if (!state) { return 'missing'; }
        (state.listeners || []).forEach(function(entry) {
            document.removeEventListener(entry[0], entry[1], true);
        });
        try { delete window['${Browser4WebDriver.TRUSTED_CLICK_PROBE}']; } catch (e) { }
        return state.trusted > 0 ? 'trusted' : 'none';
    })()
""".trimIndent()

/**
 * The `function()` body evaluated with `this` bound to a drag element.
 * Returns the element's viewport center, a stable CSS path that
 * re-locates the same element Browser4WebDriver.from the top document at drag time, and
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
            vh: window.innerHeight,
            w: r.width,
            h: r.height,
            hit: (function() {
                var at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                return !!(at && (at === this || this.contains(at)));
            }).call(this)
        });
    }
    """.trimIndent()

/**
 * Whether the element described by [point]'s CSS path is the topmost element at
 * [point]'s coordinates.
 *
 * [dragCenterJs] answers the same question for the element's *centre*, which is where the
 * element is resolved; a trusted click presses at the jittered hover point instead, so the
 * test has to be repeated there (see `trustedClickPoint`). Only main-frame elements reach
 * this probe ([Browser4WebDriver.DragCenter.inFrame] is rejected first), so the top document's
 * `querySelector` resolves the path.
 */
internal fun hitTestJs(point: Browser4WebDriver.DragCenter): String = """
    (function() {
        var el = null;
        try { el = document.querySelector('${escapeJsString(point.cssPath)}'); } catch (e) { return 'no-element'; }
        if (!el) { return 'no-element'; }
        var at = document.elementFromPoint(${point.x}, ${point.y});
        return (at && (at === el || el.contains(at))) ? 'hit' : 'miss';
    })()
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
 *    reorder handlers decide insert-before/insert-after Browser4WebDriver.from the drop
 *    point's y relative to child centers, so the ±2px jitter around a
 *    center point makes placement a coin flip; an edge-region point
 *    resolves deterministically (`--at bottom` of an item = insert
 *    after it, `--at top` = insert before it).  For targets shorter
 *    than ~5px the edge region is degenerate and the center point is
 *    kept.  When positioned, two intermediate `dragover` events sweep
 *    Browser4WebDriver.from the source point toward the drop point so handlers that
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
      var u = window.${Browser4WebDriver.PULSAR_UTILS_FUNCTION};
      var url = document.URL || '';
      if (!u || typeof u.getAnnotatedHTML !== 'function') {
        return '${Browser4WebDriver.VI_DATA_UNAVAILABLE}${Browser4WebDriver.fieldSeparatorJsEscape}${Browser4WebDriver.fieldSeparatorJsEscape}' + url;
      }
      var links = u._captureMetaLinks || {};
      var stored = links['${Browser4WebDriver.CAPTURE_META_LINK_REL}'] || '';
      var status;
      if (u._viDataComputed === true) {
        status = '${Browser4WebDriver.VI_DATA_COMPUTED}';
      } else if (typeof u.compute !== 'function') {
        status = '${Browser4WebDriver.VI_DATA_UNAVAILABLE}';
      } else if (!document.body || !document.body.firstChild) {
        status = '${Browser4WebDriver.VI_DATA_NOT_READY}';
      } else {
        try { u.compute(); } catch (e) { /* reported as failed below */ }
        status = u._viDataComputed === true ? '${Browser4WebDriver.VI_DATA_COMPUTED}' : '${Browser4WebDriver.VI_DATA_FAILED}';
      }
      return status + '${Browser4WebDriver.fieldSeparatorJsEscape}' + stored + '${Browser4WebDriver.fieldSeparatorJsEscape}' + url;
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
      var u = window.${Browser4WebDriver.PULSAR_UTILS_FUNCTION};
      if (!u) return false;
      u._captureMetaLinks = u._captureMetaLinks || {};
      u._captureMetaLinks['${Browser4WebDriver.CAPTURE_META_LINK_REL}'] = '${escapeJsString(normalizedUri)}';
      return true;
    })()
    """.trimIndent()
}
