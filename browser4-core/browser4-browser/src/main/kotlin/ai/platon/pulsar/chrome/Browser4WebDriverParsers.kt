package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import kotlin.random.Random

/**
 * Parsers and converters for the values [Browser4WebDriver] receives back from CDP.
 *
 * Each function turns an untyped `evaluate`/`evalOnSelector` payload into a typed value (or null
 * when the payload is malformed), or renders the user-facing error message the driver raises when
 * a locator resolves to something unusable. The functions are pure, so the payload handling is
 * testable without a browser.
 */

internal object Browser4WebDriverParsers {

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
 * Interpret the [Browser4WebDriverScripts.inputTargetProbeJs] result for an input command.
 *
 * The JS body of a fill/type is a no-op when `this` resolves to nothing
 * (or the element refuses input), and `evaluateValue` then yields `null`,
 * which is indistinguishable Browser4WebDriver.from a successful write for every caller up
 * the stack — including the CLI, which would print "✓ Filled ..." and exit
 * 0 for a selector that matched nothing.  Interpreting the probe here keeps
 * that contract in one place instead of once per caller.
 *
 * @param action the user-facing command name used as the message prefix
 * @param selector the selector as the caller typed it
 * @param probe the raw [Browser4WebDriverScripts.inputTargetProbeJs] result
 * @return null when the target can receive input, otherwise the message to throw
 */
internal fun inputTargetError(action: String, selector: String, probe: Any?): String? {
    val map = probe as? Map<*, *>
    if (map == null || map["found"] != true) {
        return "$action: no element found for selector [$selector]. " +
            "The selector may be stale — re-run `snapshot` to refresh refs."
    }
    val disabled = map["disabled"] == true
    val readOnly = map["readOnly"] == true
    if (disabled || readOnly) {
        return "$action: target [$selector] is ${if (disabled) "disabled" else "read-only"} " +
            "— user input is blocked."
    }
    return null
}

/**
 * Interpret the `evaluateValue(selector, "function(){ return this != null; }")`
 * probe used by the click-family overrides ([Browser4WebDriver.click],
 * [Browser4WebDriver.dblclick], [Browser4WebDriver.hover]) before they
 * fall back to the upstream synthetic DOM path.  The upstream click
 * reports success for a selector that matches nothing — the CLI prints
 * "✓ Clicked ..." and exits 0 while the page saw nothing — so the probe
 * makes the whole mouse family fail loudly, exactly like [inputTargetError]
 * does for the input family.
 *
 * @param action the user-facing command name used as the message prefix
 * @param selector the selector as the caller typed it
 * @param exists the raw probe result (`true` when the selector resolved to an element)
 * @return null when the target exists, otherwise the user-facing error message
 */
internal fun pointerTargetError(action: String, selector: String, exists: Any?): String? =
    if (exists == true) {
        null
    } else {
        "$action: no element found for selector [$selector]. " +
            "The selector may be stale — re-run `snapshot` to refresh refs."
    }

/**
 * Normalize a cookie Browser4WebDriver.from a storage-state JSON payload for
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
 * @param cookie Raw cookie entry Browser4WebDriver.from the storage-state payload.
 * @return A map with the canonical `Network.setCookies` field set.
 */
internal fun normalizeStorageStateCookie(cookie: Map<String, Any?>): Map<String, Any?> {
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

/** Target type for one raw CDP cookie object. */
internal val COOKIE_MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}

/**
 * Extract the cookie list Browser4WebDriver.from a raw `Network.getAllCookies` CDP result.
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
internal fun extractCookiesFromCdpResult(result: Any?): List<Map<String, Any?>> {
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
internal fun isDocumentOriginReady(evaluatedOrigin: String?, targetOrigin: String): Boolean =
    !evaluatedOrigin.isNullOrBlank() && evaluatedOrigin.trim() == targetOrigin

/**
 * Parse the JSON produced by [Browser4WebDriverScripts.dragCenterJs] into a [Browser4WebDriver.DragCenter].
 * Returns null when the value is missing, malformed, or lacks a usable
 * CSS path (which makes the element unre-locatable at drag time).
 */
internal fun parseDragCenter(value: Any?): Browser4WebDriver.DragCenter? {
    val json = value as? String ?: return null
    val node = runCatching { pulsarObjectMapper().readTree(json) }.getOrNull() ?: return null
    val x = node.get("x")?.takeIf { it.isNumber }?.asDouble() ?: return null
    val y = node.get("y")?.takeIf { it.isNumber }?.asDouble() ?: return null
    val cssPath = node.get("cssPath")?.asText()?.takeIf { it.isNotBlank() } ?: return null
    return Browser4WebDriver.DragCenter(
        x = x,
        y = y,
        cssPath = cssPath,
        inFrame = node.get("inFrame")?.asBoolean() ?: false,
        viewportWidth = node.get("vw")?.takeIf { it.isNumber }?.asInt() ?: 0,
        viewportHeight = node.get("vh")?.takeIf { it.isNumber }?.asInt() ?: 0,
        width = node.get("w")?.takeIf { it.isNumber }?.asDouble() ?: 0.0,
        height = node.get("h")?.takeIf { it.isNumber }?.asDouble() ?: 0.0,
        hit = node.get("hit")?.asBoolean() ?: false,
    )
}

/**
 * Nudge the element center ([x], [y]) by a random offset of at most
 * `min(jitter, box/2 - inset)` per axis, so the result stays inside the element of the given
 * [width]/[height] box.
 *
 * An element whose box is unknown (0) or too small to hold any offset keeps the exact
 * center: moving the pointer outside the element would break the CSS `:hover` state this
 * move exists to establish (see `movePointerToClickTarget`), which is worse than a
 * repeatable coordinate.
 *
 * @param nextOffset produces a uniform offset in `[-range, range]`; injected in tests
 */
internal fun jitteredPointerPosition(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    jitter: Double = Browser4WebDriver.POINTER_JITTER_PX,
    nextOffset: (Double) -> Double = { range -> Random.nextDouble(-range, range) },
): Pair<Double, Double> {
    val maxOffset = if (width > 0.0 && height > 0.0) {
        minOf(jitter, minOf(width, height) / 2.0 - Browser4WebDriver.POINTER_JITTER_EDGE_INSET_PX).coerceAtLeast(0.0)
    } else {
        0.0
    }

    if (maxOffset <= 0.0) {
        return x to y
    }

    return (x + nextOffset(maxOffset)) to (y + nextOffset(maxOffset))
}

/**
 * Whether a click at [point] may be dispatched as trusted CDP input.
 *
 * [point] must carry the hit result for the exact coordinates it is to be pressed at —
 * [Browser4WebDriverScripts.dragCenterJs] supplies it for the resolved centre, [Browser4WebDriverScripts.hitTestJs] for the jittered hover
 * point a click actually uses.
 *
 * Two conditions rule it out, both because the coordinates would then be wrong:
 *
 * - frame-resident elements: `getBoundingClientRect` is frame-relative while
 *   `Input.dispatchMouseEvent` takes main-frame viewport coordinates;
 * - an element that is not hit at that point (occluded by an overlay, `pointer-events:
 *   none`, moved by an async layout shift): the trusted click would land on whatever is on
 *   top and silently click an unrelated element. The DOM fallback dispatches on the element
 *   itself and has no such failure mode.
 */
internal fun canClickWithTrustedInput(point: Browser4WebDriver.DragCenter?): Boolean =
    point != null && !point.inFrame && point.hit

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
 * Render the JSON produced by [Browser4WebDriverScripts.dragPositionReportJs] into a
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
// the driver tell "nothing to do" apart Browser4WebDriver.from "nothing that can be done",
// so the failure is logged instead of being smuggled into an unannotated
// HTML string.
// ---------------------------------------------------------------------

/**
 * The vi state of the live document as reported by [Browser4WebDriverScripts.viDataStatusJs]:
 * the [status], the page URL currently stored for serialization
 * ([storedUri], blank when none is stored), and the live document URL
 * ([documentUrl]).
 */

/**
 * Parse a [Browser4WebDriverScripts.viDataStatusJs] result, or null when the evaluation produced
 * something unexpected (a truncated or non-string result).
 */
internal fun parseViDataProbe(value: Any?): ViDataProbe? {
    val text = value as? String ?: return null
    val parts = text.split(Browser4WebDriver.VI_DATA_FIELD_SEPARATOR)
    if (parts.size != 3) return null
    return ViDataProbe(parts[0], parts[1], parts[2])
}

/**
 * Whether a vi failure on [documentUrl] still has to be reported, given
 * the URL of the last reported failure.  One warning per document keeps
 * a page that cannot be annotated Browser4WebDriver.from flooding the log on every read.
 */
internal fun shouldReportViFailure(lastReportedUrl: String?, documentUrl: String): Boolean =
    lastReportedUrl != documentUrl

/**
 * Whether the HAR timing parts of one entry — [knownMillis], the sum of
 * its positive parts — are implausible for a request whose total is
 * [totalMillis].
 *
 * The HAR `timings` parts decompose the entry's `time`, so a sum that
 * dwarfs the total can only be the 1000x unit error of
 * `HarBuilder.deltaMillis`, never a measurement. The 4x + 100 ms margin
 * keeps clock skew and rounding out of the decision, and an unknown
 * total (`-1`, a request that never finished) is never rewritten.
 */
internal fun harTimingsAreInflated(totalMillis: Long, knownMillis: Long): Boolean =
    totalMillis > 0 && knownMillis > totalMillis * 4 + 100

/**
 * One route as [Browser4WebDriver.networkRoute] reports it and as
 * [Browser4WebDriver.networkRoutes] lists it.
 *
 * The action is the point: `--abort` and `--body` routes used to be
 * echoed as the same `{"routed": pattern}`, so an operator auditing
 * several routes could not tell them apart.
 *
 * @param routes the resulting route count, or null for a listing entry
 */
internal fun routeSummary(
    urlPattern: String,
    abort: Boolean,
    contentType: String?,
    resourceTypes: List<String>,
    routes: Int?,
): Map<String, Any?> {
    val summary = linkedMapOf<String, Any?>(
        "pattern" to urlPattern,
        "action" to if (abort) "abort" else "body",
    )
    if (contentType != null) {
        summary["contentType"] = contentType
    }
    summary["resourceTypes"] = resourceTypes
    if (routes != null) {
        summary["routes"] = routes
    }
    return summary
}
}

internal data class ViDataProbe(val status: String, val storedUri: String, val documentUrl: String)
