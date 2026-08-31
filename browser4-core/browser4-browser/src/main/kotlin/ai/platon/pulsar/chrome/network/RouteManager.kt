package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.common.getLogger
import java.lang.ref.WeakReference
import java.util.Base64
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Request routing / interception for one tab, modeled on agent-browser's
 * `network route` / `network unroute`.
 *
 * Routes are matched against the CDP `Fetch` domain: every route's URL pattern
 * is pushed into `Fetch.enable` (patterns are replaced on each change), and
 * paused requests are resolved in registration order:
 *
 * - `abort` → `Fetch.failRequest` with `Failed`.
 * - a mock response → `Fetch.fulfillRequest` (status, headers, base64 body).
 * - otherwise → `Fetch.continueRequest` (the request proceeds untouched).
 *
 * ## URL patterns
 *
 * Mirrors agent-browser's matching:
 * - `*` matches every URL.
 * - a pattern without `*` matches URLs **containing** the text.
 * - a pattern with `*` is a glob: consecutive `*` collapse, and the segments
 *   must appear in order; a pattern that does not start with `*` anchors the
 *   URL start, one that does not end with `*` anchors the URL end.
 *
 * ## Resource types
 *
 * A route with `resourceTypes` only acts on requests whose CDP resource type
 * (e.g. `XHR`, `Fetch`, `Script`) is in the list (case-insensitive). Requests
 * that Chrome pauses (URL matched a pattern) but no route fully matches are
 * continued unchanged.
 *
 * ## Threading
 *
 * Paused requests arrive on the transport's event dispatcher coroutine; tool
 * calls arrive on arbitrary request threads. The route table is guarded by a
 * single lock, and the paused-request handler never throws.
 *
 * @param browserProtocol The tab's protocol facade, used for the Fetch domain.
 */
class RouteManager(
    browserProtocol: BrowserProtocol,
) {
    /**
     * Weak reference to the tab protocol; see [NetworkObserver] for why.
     */
    private val browserProtocolRef = WeakReference(browserProtocol)
    private val browserProtocol: BrowserProtocol
        get() = browserProtocolRef.get() ?: error("RouteManager: tab protocol has been disposed")

    companion object {
        private val logger = getLogger(RouteManager::class)

        /**
         * One route manager per tab protocol, shared by every driver wrapping
         * that tab — the base event dispatcher keeps only ONE listener per
         * event key, so a second `Fetch.requestPaused` subscriber would
         * silently never fire. Weak keys; the manager holds the protocol
         * weakly so it never pins the map entry.
         */
        private val managersByProtocol =
            java.util.Collections.synchronizedMap(WeakHashMap<BrowserProtocol, RouteManager>())

        /** The manager for [browserProtocol], creating one on first use. */
        fun forProtocol(browserProtocol: BrowserProtocol): RouteManager {
            return managersByProtocol.computeIfAbsent(browserProtocol) { RouteManager(it) }
        }
    }

    /** Mock response payload for a route. */
    data class RouteResponse(
        val status: Int? = null,
        val body: String? = null,
        val contentType: String? = null,
        val headers: Map<String, String>? = null,
    )

    /** One routing rule, in registration order. */
    data class RouteEntry(
        val urlPattern: String,
        val response: RouteResponse? = null,
        val abort: Boolean = false,
        val resourceTypes: List<String> = emptyList(),
    )

    private val lock = Any()
    private val routes = mutableListOf<RouteEntry>()
    private val listeners = CopyOnWriteArrayList<EventListener>()

    @Volatile
    private var fetchEnabled = false

    /** Guards [refreshFetch] so the paused-request listener is registered once. */
    @Volatile
    private var listenerRegistered = false

    /**
     * Add a route. Requests matching [urlPattern] (and, when given,
     * [resourceTypes]) are aborted when [abort] is true, otherwise answered
     * with [response] when provided, otherwise continued unchanged.
     *
     * @return `{ "routed": urlPattern }`, matching agent-browser.
     */
    suspend fun route(
        urlPattern: String,
        response: RouteResponse? = null,
        abort: Boolean = false,
        resourceTypes: List<String> = emptyList(),
    ): Map<String, Any?> {
        require(urlPattern.isNotBlank()) { "urlPattern must not be blank" }
        synchronized(lock) {
            routes += RouteEntry(urlPattern, response, abort, resourceTypes)
        }
        refreshFetch()
        logger.info("Route added: {} (abort={}, types={})", urlPattern, abort, resourceTypes)
        return mapOf("routed" to urlPattern)
    }

    /**
     * Remove routes. Without [urlPattern] every route is removed and Fetch
     * interception is disabled.
     *
     * @return `{ "unrouted": urlPattern | "all" }`, matching agent-browser.
     */
    suspend fun unroute(urlPattern: String? = null): Map<String, Any?> {
        synchronized(lock) {
            if (urlPattern == null) {
                routes.clear()
            } else {
                routes.removeAll { it.urlPattern == urlPattern }
            }
        }
        refreshFetch()
        logger.info("Route removed: {}", urlPattern ?: "all")
        return mapOf("unrouted" to (urlPattern ?: "all"))
    }

    /** Snapshot of the active routes, in registration order. */
    fun routeList(): List<RouteEntry> = synchronized(lock) { routes.toList() }

    /** Whether Fetch interception is currently enabled on the tab. */
    val isFetchEnabled: Boolean get() = fetchEnabled

    /**
     * Register the `Fetch.requestPaused` listener WITHOUT enabling Fetch
     * interception. The base library's own `Fetch.requestPaused` subscriber
     * (its `NetworkManager`) registers on first navigation, and the event
     * dispatcher keeps only ONE listener per key — registering at driver
     * creation claims the slot first. Idempotent.
     */
    fun preRegister() {
        if (listenerRegistered) return
        listeners += browserProtocol.onRequestPaused { event -> onRequestPaused(event) }
        listenerRegistered = true
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Re-apply the Fetch interception state: `Fetch.disable` when no routes
     * remain, otherwise re-enable with the full pattern set (Fetch.enable
     * replaces patterns on every call, so this is how removals take effect).
     */
    private suspend fun refreshFetch() {
        val patterns = synchronized(lock) { routes.map { collapseWildcards(it.urlPattern) } }
        if (patterns.isEmpty()) {
            if (fetchEnabled) {
                browserProtocol.executeCdpCommand("Fetch.disable", null)
                fetchEnabled = false
                logger.info("Fetch interception disabled (no routes)")
            }
            return
        }
        if (!fetchEnabled) {
            // The paused-request listener is registered once (see preRegister);
            // it must survive unroute-all → route cycles, otherwise every
            // paused request would be handled by a second listener too (the
            // second failRequest/fulfillRequest on an already-resolved id
            // would log "Invalid InterceptionId" noise).
            preRegister()
            fetchEnabled = true
        }
        browserProtocol.fetchEnable(
            patterns.map { RequestPattern(urlPattern = it, resourceType = null, requestStage = null) },
            handleAuthRequests = false,
        )
    }

    internal suspend fun onRequestPaused(event: RequestPaused) {
        try {
            val url = event.request.url
            val resourceType = event.resourceType?.name ?: "Other"
            val snapshot = synchronized(lock) { routes.toList() }

            for (route in snapshot) {
                if (!routeUrlMatches(route.urlPattern, url)) continue
                if (route.resourceTypes.isNotEmpty() &&
                    route.resourceTypes.none { it.equals(resourceType, ignoreCase = true) }
                ) {
                    continue
                }

                if (route.abort) {
                    browserProtocol.failRequest(event.requestId, ErrorReason.FAILED)
                    return
                }
                val response = route.response
                if (response != null) {
                    val headers = buildList {
                        response.contentType?.let { add(HeaderEntry("Content-Type", it)) }
                        response.headers?.forEach { (name, value) -> add(HeaderEntry(name, value)) }
                    }
                    val body = Base64.getEncoder()
                        .encodeToString((response.body ?: "").toByteArray(Charsets.UTF_8))
                    browserProtocol.fulfillRequest(
                        requestId = event.requestId,
                        responseCode = response.status ?: 200,
                        responseHeaders = headers,
                        body = body,
                    )
                    return
                }
            }

            // No route matched (e.g. URL matched a pattern but the resource
            // type filter did not) — let the request proceed.
            browserProtocol.continueRequest(event.requestId)
        } catch (e: Exception) {
            logger.warn("Failed to handle Fetch.requestPaused: {}", e.message)
            // Never leave the request paused: if the intended action failed
            // (e.g. the interception id was already resolved elsewhere),
            // continue the request unchanged so the page does not hang.
            runCatching { browserProtocol.continueRequest(event.requestId) }
                .onFailure { f -> logger.debug("Fallback continueRequest also failed: {}", f.message) }
        }
    }

    /** Collapse consecutive `*` characters (Fetch patterns reject `**`). */
    internal fun collapseWildcards(pattern: String): String {
        val sb = StringBuilder(pattern.length)
        var lastWasStar = false
        for (ch in pattern) {
            if (ch == '*') {
                if (!lastWasStar) sb.append(ch)
                lastWasStar = true
            } else {
                sb.append(ch)
                lastWasStar = false
            }
        }
        return sb.toString()
    }

    /**
     * URL pattern matching, mirroring agent-browser's `route_url_matches`:
     * `*` matches everything; plain patterns are substring matches; glob
     * patterns anchor start/end unless wildcarded and require their segments
     * in order.
     */
    internal fun routeUrlMatches(pattern: String, url: String): Boolean {
        if (pattern == "*") return true
        if (!pattern.contains('*')) return url.contains(pattern)

        val collapsed = collapseWildcards(pattern)
        val parts = collapsed.split('*').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true

        val anchoredStart = !collapsed.startsWith('*')
        val anchoredEnd = !collapsed.endsWith('*')
        var pos = 0
        var idx = 0

        if (anchoredStart) {
            val first = parts[0]
            if (!url.startsWith(first)) return false
            pos = first.length
            idx = 1
        }

        while (idx < parts.size) {
            val part = parts[idx]
            val found = url.indexOf(part, pos)
            if (found < 0) return false
            pos = found + part.length
            idx += 1
        }

        if (anchoredEnd) {
            val last = parts.last()
            return url.endsWith(last)
        }
        return true
    }
}
