package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.types.network.ResourceTiming

/**
 * A single HTTP request observed through the CDP `Network` domain, plus the
 * response metadata that arrives later.
 *
 * The class is intentionally a plain snapshot of CDP event payloads — no
 * behaviour — so it can be serialized to JSON (tool output), filtered, and
 * fed into the HAR builder.
 *
 * @property requestId CDP network request id (per target, unique while the tab lives).
 * @property url The request URL.
 * @property method The HTTP method.
 * @property resourceType CDP resource type, e.g. `Document`, `XHR`, `Fetch`, `Script`.
 * @property timestamp Wall-clock epoch millis of `requestWillBeSent`.
 * @property timestampMonotonic CDP monotonic timestamp (seconds) of `requestWillBeSent`,
 * used together with [finishedTimestampMonotonic] to compute durations.
 * @property frameId The frame that issued the request, when known.
 * @property loaderId The loader that issued the request, when known.
 * @property requestHeaders Final request headers (merged from `requestWillBeSentExtraInfo`).
 * @property postData Request body when the page sent one.
 * @property status HTTP status of the response, when received.
 * @property statusText Status text of the response, when received.
 * @property responseHeaders Final response headers (merged from `responseReceivedExtraInfo`).
 * @property mimeType MIME type of the response body.
 * @property remoteIPAddress Server IP, when reported.
 * @property remotePort Server port, when reported.
 * @property fromDiskCache Whether the response came from the HTTP cache.
 * @property protocol Protocol used, e.g. `http/1.1`.
 * @property encodedDataLength Encoded (on-the-wire) response size in bytes.
 * @property timing CDP resource timing for the request, when reported.
 * @property errorText Failure reason from `loadingFailed`, when the request failed.
 * @property canceled Whether the request was canceled (from `loadingFailed`).
 * @property finished Whether `loadingFinished`/`loadingFailed` was observed.
 * @property finishedTimestampMonotonic CDP monotonic timestamp (seconds) of the finish event.
 * @property responseBody Response body captured for HAR recording / detail queries.
 * @property responseBodyBase64 Whether [responseBody] is base64-encoded binary.
 * @property responseBodyBytes Byte length of the response body, recorded even
 * when the body itself was skipped (size budget) so HAR metadata stays accurate.
 */
data class TrackedNetworkRequest(
    val requestId: String,
    val url: String,
    val method: String,
    val resourceType: String,
    val timestamp: Long,
    val timestampMonotonic: Double = 0.0,
    val frameId: String? = null,
    val loaderId: String? = null,
    val requestHeaders: Map<String, Any?> = emptyMap(),
    val postData: String? = null,
    val status: Int? = null,
    val statusText: String? = null,
    val responseHeaders: Map<String, Any?> = emptyMap(),
    val mimeType: String? = null,
    val remoteIPAddress: String? = null,
    val remotePort: Int? = null,
    val fromDiskCache: Boolean? = null,
    val protocol: String? = null,
    val encodedDataLength: Double? = null,
    val timing: ResourceTiming? = null,
    val errorText: String? = null,
    val canceled: Boolean? = null,
    val finished: Boolean = false,
    val finishedTimestampMonotonic: Double? = null,
    val responseBody: String? = null,
    val responseBodyBase64: Boolean = false,
    val responseBodyBytes: Long? = null,
) {
    /** The response body as plain text, decoding base64 binary bodies on demand. */
    fun responseBodyText(): String? {
        val body = responseBody ?: return null
        if (!responseBodyBase64) return body
        return runCatching { String(java.util.Base64.getDecoder().decode(body), Charsets.UTF_8) }.getOrNull()
    }

    /**
     * A copy of this request with the response body stripped, for list results:
     * body payloads are only exposed through the request-detail query (bodies
     * can be megabytes; a listing must not ship them).
     */
    fun withoutBody(): TrackedNetworkRequest = copy(responseBody = null, responseBodyBase64 = false)
}
