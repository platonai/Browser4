package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.events.network.LoadingFailed
import ai.platon.cdt.kt.protocol.events.network.LoadingFinished
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSentExtraInfo
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.events.network.ResponseReceivedExtraInfo
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/**
 * Observes the CDP `Network` domain of one tab: tracks every request/response
 * in a bounded in-memory store and optionally records a HAR session with
 * response bodies.
 *
 * ## Wiring
 *
 * The observer attaches to the tab's [BrowserProtocol] once (lazily, on first
 * use) by enabling the `Network` domain and subscribing through the typed
 * `on*` listener methods — the same consumption path the base library's own
 * `NetworkManager` uses:
 *
 * - `Network.requestWillBeSent` / `Network.requestWillBeSentExtraInfo` — create
 *   and complete the request side of a tracked entry.
 * - `Network.responseReceived` / `Network.responseReceivedExtraInfo` — fill in
 *   the response side.
 * - `Network.loadingFinished` / `Network.loadingFailed` — mark the entry
 *   finished; while a HAR recording is active, response bodies are fetched via
 *   `Network.getResponseBody` before Chrome evicts them (e.g. on navigation).
 *
 * ## Threading
 *
 * CDP events arrive on the transport's event dispatcher coroutine; tool calls
 * arrive on arbitrary request threads. All store mutations are guarded by a
 * single lock, and event handlers never throw (a failing handler must not
 * break the transport's event dispatch loop).
 *
 * @param browserProtocol The tab's protocol facade, used for the Network domain
 * and body retrieval.
 */
class NetworkObserver(
    private val browserProtocol: BrowserProtocol,
) {
    companion object {
        private val logger = getLogger(NetworkObserver::class)

        /** Upper bound on tracked requests; oldest entries are evicted first. */
        const val MAX_TRACKED_REQUESTS = 5_000

        /** Bodies larger than this are never captured for request detail. */
        const val MAX_DETAIL_BODY_BYTES = 1 * 1024 * 1024
    }

    private val lock = Any()
    private val requests = mutableListOf<TrackedNetworkRequest>()
    private val listeners = CopyOnWriteArrayList<EventListener>()

    @Volatile
    private var enabled = false

    /** Whether the buffered `Network.enable` (large buffers) has been applied. */
    @Volatile
    private var bufferedEnableApplied = false

    /** Whether the CDP event listeners are registered (exactly once). */
    @Volatile
    private var listenerRegistered = false

    /** Completed when the current [ensureEnabled] run finishes; null when idle. */
    private var enablingDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var harRecording = false
    private var harContentMode = HarContentMode.NONE
    private var harEmbeddedBodyBytes = 0L

    /** Whether a HAR recording is currently active. */
    val isHarRecording: Boolean get() = harRecording

    /**
     * Enable the Network domain and attach event listeners. Idempotent and
     * safe under concurrent first calls: the first caller enables (listeners
     * are registered *before* the domain so no event can slip through the
     * gap), every concurrent caller waits for that run to finish instead of
     * proceeding on a half-enabled observer.
     *
     * @param maxTotalBufferSize / [maxResourceBufferSize] optional `Network.enable`
     * buffer sizes; used by HAR recording so response bodies survive until
     * [harStop] drains them. When given, enabling happens in a single
     * `Network.enable` call so a concurrent plain enable can never override
     * the larger buffers. When the domain is already enabled *without* the
     * requested buffers (e.g. `network requests` ran first), the domain is
     * re-enabled with the buffers — matching agent-browser, whose
     * `har start` always re-applies its buffer sizes.
     */
    suspend fun ensureEnabled(
        maxTotalBufferSize: Long? = null,
        maxResourceBufferSize: Long? = null,
    ) {
        val buffersRequested = maxTotalBufferSize != null
        while (true) {
            if (enabled && (!buffersRequested || bufferedEnableApplied)) return
            val (isCreator, deferred) = synchronized(lock) {
                if (enabled && (!buffersRequested || bufferedEnableApplied)) return
                val existing = enablingDeferred
                if (existing != null) {
                    false to existing
                } else {
                    true to CompletableDeferred<Unit>().also { enablingDeferred = it }
                }
            }
            if (!isCreator) {
                // Wait for the in-flight enabling run, then re-evaluate: that
                // run may not have applied the configuration we need.
                deferred.await()
                continue
            }
            try {
                // Register listeners first: events can only arrive once the
                // domain is enabled, so nothing is lost between the two calls.
                registerListeners()
                if (buffersRequested) {
                    browserProtocol.executeCdpCommand(
                        "Network.enable",
                        mapOf(
                            "maxTotalBufferSize" to maxTotalBufferSize,
                            "maxResourceBufferSize" to (maxResourceBufferSize ?: 0L),
                        ),
                    )
                    bufferedEnableApplied = true
                } else {
                    browserProtocol.networkEnable()
                }
                enabled = true
                logger.info("Network observer enabled for tab")
                return
            } finally {
                synchronized(lock) {
                    enablingDeferred = null
                    deferred.complete(Unit)
                }
            }
        }
    }

    /**
     * List tracked requests, optionally filtered.
     *
     * Redirect hops are kept as separate entries (one per
     * `requestWillBeSent`, matching agent-browser), so a redirected request
     * may appear multiple times with the same requestId and different URLs.
     *
     * @param filter Only requests whose URL contains this text (case-insensitive).
     * @param type Only requests whose CDP resource type is in this comma-separated list.
     * @param method Only requests with this HTTP method (case-insensitive).
     * @param status Status filter: exact code (`200`), wildcard (`2xx`), or range (`400-499`).
     * @param clear When true, drop all tracked requests first.
     */
    fun networkRequests(
        filter: String? = null,
        type: String? = null,
        method: String? = null,
        status: String? = null,
        clear: Boolean = false,
    ): List<TrackedNetworkRequest> {
        if (clear) {
            synchronized(lock) { requests.clear() }
        }
        val types = type?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
        val filterLower = filter?.lowercase()?.takeIf { it.isNotBlank() }
        val methodUpper = method?.uppercase()?.takeIf { it.isNotBlank() }
        val statusMatcher = StatusFilter.parse(status)

        return synchronized(lock) {
            requests.filter { request ->
                (filterLower == null || request.url.lowercase().contains(filterLower)) &&
                    (types.isEmpty() || request.resourceType.lowercase() in types) &&
                    (methodUpper == null || request.method.uppercase() == methodUpper) &&
                    statusMatcher.matches(request.status)
            }
        }
    }

    /**
     * Full detail of one tracked request (the most recent entry for the id,
     * i.e. the current hop of a redirect chain), including the response body
     * when it can be retrieved (fetched once on demand, capped at
     * [MAX_DETAIL_BODY_BYTES]).
     *
     * @throws IllegalArgumentException when the request id is unknown.
     */
    suspend fun networkRequestDetail(requestId: String): Map<String, Any?> {
        ensureEnabled()
        val request = synchronized(lock) { lastById(requestId) }
            ?: throw IllegalArgumentException("Unknown network request id: $requestId")
        // Fetch the body on demand for finished, non-failed requests that have
        // not been captured yet. responseBodyBytes != null means the body was
        // already attempted (and skipped for size) — do not refetch it.
        if (request.finished && request.errorText == null &&
            request.responseBody == null && request.responseBodyBytes == null
        ) {
            fetchResponseBody(requestId)
        }
        val updated = synchronized(lock) { lastById(requestId) ?: request }
        return mapOf(
            "request" to updated,
            "responseBody" to (updated.responseBodyText() ?: ""),
        )
    }

    /**
     * Start a HAR recording session. Requests that finish afterwards get their
     * response bodies captured (subject to the content mode and size budgets).
     *
     * The Network domain is enabled with larger buffers (like agent-browser)
     * so Chrome keeps response bodies alive until [harStop] drains them.
     *
     * @param contentMode Which bodies to embed: `none`, `text`, or `all`.
     */
    suspend fun harStart(contentMode: HarContentMode): Map<String, Any?> {
        // Enabling with the larger buffers happens in the single
        // Network.enable call inside ensureEnabled, so a concurrent plain
        // enable can never reset them afterwards.
        ensureEnabled(
            maxTotalBufferSize = 100_000_000,
            maxResourceBufferSize = 10_000_000,
        )
        synchronized(lock) {
            harRecording = true
            harContentMode = contentMode
            harEmbeddedBodyBytes = 0L
        }
        logger.info("HAR recording started (content={})", contentMode.apiName)
        return mapOf(
            "recording" to true,
            "contentMode" to contentMode.apiName,
        )
    }

    /**
     * Stop the active HAR recording and build the HAR document from every
     * request observed since the observer was created.
     *
     * @return The HAR document plus recording metadata.
     */
    suspend fun harStop(): Map<String, Any?> {
        ensureEnabled()
        val (mode, snapshot) = synchronized(lock) {
            val mode = harContentMode
            harRecording = false
            mode to requests.toList()
        }
        logger.info("HAR recording stopped (content={}, entries={})", mode.apiName, snapshot.size)
        val har = HarBuilder.build(snapshot, mode, browser = browserMetadata())
        return mapOf(
            "recording" to false,
            "contentMode" to mode.apiName,
            "entries" to snapshot.size,
            "har" to har,
        )
    }

    /**
     * Best-effort browser metadata for the HAR `log.browser` object
     * (`Browser.getVersion`). Returns null when unavailable.
     */
    private suspend fun browserMetadata(): Map<String, Any?>? {
        return try {
            when (val result = browserProtocol.executeCdpCommand("Browser.getVersion", null)) {
                is Map<*, *> -> {
                    val product = result["product"]?.toString() ?: return null
                    val slash = product.indexOf('/')
                    val name = if (slash > 0) product.substring(0, slash) else product
                    val version = if (slash > 0) product.substring(slash + 1) else ""
                    mapOf(
                        "name" to name,
                        "version" to version,
                        "userAgent" to (result["userAgent"]?.toString() ?: ""),
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.debug("Failed to query browser version for HAR: {}", e.message)
            null
        }
    }

    // ------------------------------------------------------------------
    // Event handlers — called from the transport's event dispatcher
    // ------------------------------------------------------------------

    internal fun onRequestWillBeSent(event: RequestWillBeSent) {
        try {
            val request = event.request
            val timestampMillis = if (event.wallTime > 0) (event.wallTime * 1000.0).toLong()
            else System.currentTimeMillis()
            val tracked = TrackedNetworkRequest(
                requestId = event.requestId,
                url = request.url,
                method = request.method,
                resourceType = event.type?.name ?: "Other",
                timestamp = timestampMillis,
                timestampMonotonic = event.timestamp,
                frameId = event.frameId,
                loaderId = event.loaderId,
                requestHeaders = request.headers ?: emptyMap(),
                postData = request.postData,
            )
            synchronized(lock) {
                // Keep every hop of a redirect chain as its own entry, like
                // agent-browser's tracked_requests list.
                requests.add(tracked)
                evictIfNeeded()
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.requestWillBeSent: {}", e.message)
        }
    }

    internal fun onRequestWillBeSentExtraInfo(event: RequestWillBeSentExtraInfo) {
        try {
            val headers = event.headers ?: return
            synchronized(lock) {
                val index = lastIndexById(event.requestId) ?: return
                requests[index] = requests[index].copy(requestHeaders = headers)
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.requestWillBeSentExtraInfo: {}", e.message)
        }
    }

    internal fun onResponseReceived(event: ResponseReceived) {
        try {
            val response = event.response
            synchronized(lock) {
                val index = lastIndexById(event.requestId) ?: return
                val current = requests[index]
                requests[index] = current.copy(
                    status = response.status,
                    statusText = response.statusText,
                    responseHeaders = response.headers ?: current.responseHeaders,
                    mimeType = response.mimeType,
                    remoteIPAddress = response.remoteIPAddress,
                    remotePort = response.remotePort,
                    fromDiskCache = response.fromDiskCache,
                    protocol = response.protocol,
                    encodedDataLength = response.encodedDataLength.takeIf { it > 0.0 },
                    timing = response.timing,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.responseReceived: {}", e.message)
        }
    }

    internal fun onResponseReceivedExtraInfo(event: ResponseReceivedExtraInfo) {
        try {
            val headers = event.headers ?: return
            synchronized(lock) {
                val index = lastIndexById(event.requestId) ?: return
                requests[index] = requests[index].copy(responseHeaders = headers)
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.responseReceivedExtraInfo: {}", e.message)
        }
    }

    internal suspend fun onLoadingFinished(event: LoadingFinished) {
        try {
            synchronized(lock) {
                val index = lastIndexById(event.requestId) ?: return
                val current = requests[index]
                requests[index] = current.copy(
                    finished = true,
                    finishedTimestampMonotonic = event.timestamp,
                    encodedDataLength = current.encodedDataLength ?: event.encodedDataLength.takeIf { it > 0.0 },
                )
            }
            if (shouldCaptureBodies()) {
                fetchResponseBody(event.requestId)
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.loadingFinished: {}", e.message)
        }
    }

    internal fun onLoadingFailed(event: LoadingFailed) {
        try {
            synchronized(lock) {
                val index = lastIndexById(event.requestId) ?: return
                val current = requests[index]
                requests[index] = current.copy(
                    finished = true,
                    finishedTimestampMonotonic = event.timestamp,
                    errorText = event.errorText,
                    canceled = event.canceled,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to handle Network.loadingFailed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun registerListeners() {
        // Called only from the single-flight creator of ensureEnabled, so a
        // plain flag guard suffices — no concurrent registration is possible.
        if (listenerRegistered) return
        listeners += browserProtocol.onRequestWillBeSent { event -> onRequestWillBeSent(event) }
        listeners += browserProtocol.onRequestWillBeSentExtraInfo { event -> onRequestWillBeSentExtraInfo(event) }
        listeners += browserProtocol.onResponseReceived { event -> onResponseReceived(event) }
        listeners += browserProtocol.onResponseReceivedExtraInfo { event -> onResponseReceivedExtraInfo(event) }
        listeners += browserProtocol.onLoadingFinished { event -> onLoadingFinished(event) }
        listeners += browserProtocol.onLoadingFailed { event -> onLoadingFailed(event) }
        listenerRegistered = true
    }

    private fun shouldCaptureBodies(): Boolean = synchronized(lock) {
        harRecording && harContentMode != HarContentMode.NONE
    }

    /**
     * Fetch and store the response body of a finished request, honoring the
     * per-body and total size budgets. Failures (body evicted, non-finished,
     * protocol errors) are silent: metadata stays intact.
     */
    private suspend fun fetchResponseBody(requestId: String) {
        try {
            val result = browserProtocol.executeCdpCommand(
                "Network.getResponseBody",
                mapOf("requestId" to requestId),
            )
            val (bodyText, base64) = parseResponseBodyResult(result) ?: return
            val byteLength = if (base64) {
                runCatching { Base64.getDecoder().decode(bodyText).size }.getOrDefault(0)
            } else {
                bodyText.toByteArray(Charsets.UTF_8).size
            }
            synchronized(lock) {
                val index = lastIndexById(requestId) ?: return
                val current = requests[index]
                val recording = harRecording
                // Always remember the body size so HAR metadata stays accurate,
                // even when the body itself cannot be embedded.
                if (!recording && byteLength > MAX_DETAIL_BODY_BYTES) {
                    requests[index] = current.copy(responseBodyBytes = byteLength.toLong())
                    return
                }
                val budgetOk = !recording || harEmbeddedBodyBytes + byteLength <= HarBuilder.MAX_TOTAL_BODY_BYTES
                val perBodyOk = byteLength <= HarBuilder.MAX_BODY_BYTES
                if (recording && !(budgetOk && perBodyOk)) {
                    requests[index] = current.copy(responseBodyBytes = byteLength.toLong())
                    return
                }
                if (recording) {
                    harEmbeddedBodyBytes += byteLength
                }
                requests[index] = current.copy(
                    responseBody = bodyText,
                    responseBodyBase64 = base64,
                    responseBodyBytes = byteLength.toLong(),
                )
            }
        } catch (e: Exception) {
            // Response bodies are best-effort: Chrome evicts them on navigation,
            // and requests may be filtered by the browser's own interception.
            logger.debug("Failed to fetch response body for {}: {}", requestId, e.message)
        }
    }

    /**
     * Extract `(body, base64Encoded)` from a `Network.getResponseBody` result,
     * tolerating both a raw map and a JSON string (depending on how the RPC
     * layer surfaced the result). Returns null when the body is unavailable.
     */
    private fun parseResponseBodyResult(result: Any?): Pair<String, Boolean>? {
        return when (result) {
            is Map<*, *> -> {
                val body = result["body"]?.toString() ?: return null
                val base64 = result["base64Encoded"]?.toString()?.toBooleanStrictOrNull() ?: false
                body to base64
            }
            is String -> {
                val node = runCatching { pulsarObjectMapper().readTree(result) }.getOrNull() ?: return null
                val body = node.get("body")?.asText() ?: return null
                body to (node.get("base64Encoded")?.asBoolean() ?: false)
            }
            else -> null
        }
    }

    private fun evictIfNeeded() {
        while (requests.size > MAX_TRACKED_REQUESTS) {
            requests.removeAt(0)
        }
    }

    /** Index of the most recent entry with the given request id, or null. */
    private fun lastIndexById(requestId: String): Int? {
        for (i in requests.indices.reversed()) {
            if (requests[i].requestId == requestId) return i
        }
        return null
    }

    /** Most recent entry with the given request id, or null. */
    private fun lastById(requestId: String): TrackedNetworkRequest? {
        val index = lastIndexById(requestId) ?: return null
        return requests[index]
    }

    /** Status filter: exact code, wildcard class, or inclusive range (comma-separated specs OR-ed). */
    private class StatusFilter(private val predicate: (Int?) -> Boolean) {
        fun matches(status: Int?): Boolean = predicate(status)

        companion object {
            fun parse(spec: String?): StatusFilter {
                if (spec.isNullOrBlank()) return StatusFilter { true }
                val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) return StatusFilter { true }
                val matchers = parts.map { parseSingle(it) }
                return StatusFilter { status -> matchers.any { it.matches(status) } }
            }

            private fun parseSingle(trimmed: String): StatusFilter {
                trimmed.toIntOrNull()?.let { code ->
                    return StatusFilter { status -> status == code }
                }
                val range = Regex("""^(\d{3})\s*-\s*(\d{3})$""").matchEntire(trimmed)
                if (range != null) {
                    val from = range.groupValues[1].toInt()
                    val to = range.groupValues[2].toInt()
                    return StatusFilter { status -> status != null && status in from..to }
                }
                val wildcard = Regex("""^([1-5])xx$""", RegexOption.IGNORE_CASE).matchEntire(trimmed)
                if (wildcard != null) {
                    val hundred = wildcard.groupValues[1].toInt() * 100
                    return StatusFilter { status -> status != null && status in hundred..(hundred + 99) }
                }
                throw IllegalArgumentException(
                    "Invalid status filter '$trimmed'. Expected e.g. '200', '2xx', or '400-499'"
                )
            }
        }
    }
}
