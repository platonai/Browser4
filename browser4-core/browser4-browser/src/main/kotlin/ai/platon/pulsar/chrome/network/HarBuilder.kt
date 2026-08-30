package ai.platon.pulsar.chrome.network

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.math.roundToLong

/**
 * Which response bodies are embedded in a HAR recording.
 */
enum class HarContentMode(val apiName: String) {
    /** No response bodies at all — smallest files, fastest recording. */
    NONE("none"),
    /** Only text-like bodies (text, json, xml, javascript, form data). */
    TEXT("text"),
    /** Every body up to the per-body cap; binary content is base64-encoded. */
    ALL("all");

    companion object {
        fun parse(value: String): HarContentMode = when (value.lowercase()) {
            "none" -> NONE
            "text" -> TEXT
            "all" -> ALL
            else -> throw IllegalArgumentException(
                "Invalid HAR content mode '$value'. Valid options: all, text, none"
            )
        }
    }
}

/**
 * Builds a [HAR 1.2](https://w3c.github.io/web-performance/specs/HAR/Overview.html)
 * document from tracked network requests.
 *
 * Pure mapping code — no I/O — so it can be unit tested without a browser.
 */
object HarBuilder {

    /** Bodies larger than this are not embedded in the HAR (metadata is kept). */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** Total budget for embedded bodies across one recording session. */
    const val MAX_TOTAL_BODY_BYTES = 64 * 1024 * 1024

    private val HAR_TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val TEXT_MIME_PATTERN = Regex(
        "^(text/|application/(json|xml|javascript|x-www-form-urlencoded|graphql)|application/.*\\+json|image/svg\\+xml)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Build a HAR document.
     *
     * @param requests The tracked requests, in observation order.
     * @param contentMode The content mode the recording ran with.
     * @param totalBodyBytes The total bytes already embedded (used to enforce
     * the global budget; entries over the remaining budget keep metadata only).
     * @param creatorName Name of the recording tool.
     * @param creatorVersion Version of the recording tool.
     * @param maxTotalBodyBytes Total budget for embedded bodies (overridable for tests).
     * @param browser Browser metadata for the HAR `log.browser` object
     * (`name`, `version`, …), matching the HAR 1.2 spec.
     */
    fun build(
        requests: List<TrackedNetworkRequest>,
        contentMode: HarContentMode,
        totalBodyBytes: Long = 0L,
        creatorName: String = "Browser4",
        creatorVersion: String = "4.14",
        maxTotalBodyBytes: Long = MAX_TOTAL_BODY_BYTES.toLong(),
        browser: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        var embeddedBytes = totalBodyBytes
        val entries = mutableListOf<Map<String, Any?>>()
        for (request in requests) {
            val entry = buildEntry(request, contentMode, embeddedBytes, maxTotalBodyBytes)
            if (entry != null) {
                embeddedBytes += embeddedSizeOf(request, contentMode, maxTotalBodyBytes)
                entries.add(entry)
            }
        }
        val log = mutableMapOf<String, Any?>(
            "version" to "1.2",
            "creator" to mapOf("name" to creatorName, "version" to creatorVersion),
            "pages" to listOf(
                mapOf(
                    "startedDateTime" to formatTimestamp(requests.firstOrNull()?.timestamp ?: System.currentTimeMillis()),
                    "id" to "page_1",
                    "title" to "",
                    "pageTimings" to mapOf("onContentLoad" to -1, "onLoad" to -1),
                )
            ),
            "entries" to entries,
        )
        if (browser != null) {
            log["browser"] = browser
        }
        return mapOf("log" to log)
    }

    private fun buildEntry(
        request: TrackedNetworkRequest,
        contentMode: HarContentMode,
        embeddedBytes: Long,
        maxTotalBodyBytes: Long,
    ): Map<String, Any?>? {
        val startedMillis = request.timestamp
        val totalMillis = totalTimeMillis(request)
        val body = if (shouldEmbedBody(request, contentMode, embeddedBytes, maxTotalBodyBytes)) request.responseBody else null
        val bodyBytes = bodyByteLength(request, body)

        return mapOf(
            "startedDateTime" to formatTimestamp(startedMillis),
            "time" to totalMillis,
            "request" to buildRequest(request),
            "response" to buildResponse(request, contentMode, body, bodyBytes),
            "cache" to mapOf<String, Any?>(),
            "timings" to buildTimings(request, totalMillis),
            "serverIPAddress" to (request.remoteIPAddress ?: ""),
            "_resourceType" to request.resourceType,
            "_error" to (request.errorText ?: ""),
        )
    }

    private fun buildRequest(request: TrackedNetworkRequest): Map<String, Any?> {
        val headers = request.requestHeaders
        val queryString = parseQueryString(request.url)
        val postData = buildPostData(request, headers)
        return mapOf(
            "method" to request.method,
            "url" to request.url,
            "httpVersion" to httpVersion(request.protocol),
            "cookies" to parseCookies(firstHeader(headers, "Cookie")),
            "headers" to flattenHeaders(headers),
            "queryString" to queryString,
            "postData" to postData,
            "headersSize" to -1,
            "bodySize" to (request.postData?.toByteArray(StandardCharsets.UTF_8)?.size ?: -1),
        )
    }

    private fun buildResponse(
        request: TrackedNetworkRequest,
        contentMode: HarContentMode,
        body: String?,
        bodyBytes: Long,
    ): Map<String, Any?> {
        val headers = request.responseHeaders
        val mimeType = request.mimeType ?: ""
        // Prefer the known body byte length; fall back to the embedded body or
        // the wire size.
        val contentSize = request.responseBodyBytes
            ?: bodyByteLength(request, body).takeIf { it > 0L }
            ?: (request.encodedDataLength ?: 0.0).toLong()
        val content = mutableMapOf<String, Any?>(
            "size" to contentSize,
            "mimeType" to mimeType,
        )
        if (body != null && contentMode != HarContentMode.NONE) {
            content["text"] = body
        }
        val redirectLocation = firstHeader(headers, "Location")
        return mapOf(
            "status" to (request.status ?: 0),
            "statusText" to (request.statusText ?: ""),
            "httpVersion" to httpVersion(request.protocol),
            "cookies" to parseSetCookies(headers),
            "headers" to flattenHeaders(headers),
            "content" to content,
            "redirectURL" to (redirectLocation ?: ""),
            "headersSize" to -1,
            "bodySize" to (request.responseBodyBytes ?: -1L),
            "_transferSize" to (request.encodedDataLength ?: 0L).toLong(),
        )
    }

    private fun buildPostData(request: TrackedNetworkRequest, headers: Map<String, Any?>): Map<String, Any?>? {
        val postData = request.postData ?: return null
        val mimeType = firstHeader(headers, "Content-Type") ?: "application/octet-stream"
        return mapOf(
            "mimeType" to mimeType,
            "text" to postData,
        )
    }

    private fun buildTimings(request: TrackedNetworkRequest, totalMillis: Long): Map<String, Any?> {
        val timing = request.timing
        val dns = if (timing != null) deltaMillis(timing.dnsStart, timing.dnsEnd) else -1
        val connect = if (timing != null) deltaMillis(timing.connectStart, timing.connectEnd) else -1
        val ssl = if (timing != null) deltaMillis(timing.sslStart, timing.sslEnd) else -1
        val send = if (timing != null) deltaMillis(timing.sendStart, timing.sendEnd) else -1
        val wait = if (timing != null) deltaMillis(timing.sendEnd, timing.receiveHeadersEnd) else -1
        // Receive = what's left of the total when known; otherwise -1.
        val known = listOf(dns, connect, ssl, send, wait).filter { it >= 0 }.sum()
        val receive = if (totalMillis >= 0) maxOf(0L, totalMillis - known) else -1
        return mapOf(
            "blocked" to -1,
            "dns" to dns,
            "connect" to connect,
            "send" to send,
            "wait" to wait,
            "receive" to receive,
            "ssl" to ssl,
        )
    }

    /** Total request duration in milliseconds, or -1 when not finished. */
    private fun totalTimeMillis(request: TrackedNetworkRequest): Long {
        val start = request.timestampMonotonic
        val finish = request.finishedTimestampMonotonic ?: return -1
        if (start <= 0.0 || finish <= 0.0) return -1
        return maxOf(0L, ((finish - start) * 1000.0).toLong())
    }

    /** Whether the body should be embedded under the current content mode and budgets. */
    private fun shouldEmbedBody(
        request: TrackedNetworkRequest,
        contentMode: HarContentMode,
        embeddedBytes: Long,
        maxTotalBodyBytes: Long,
    ): Boolean {
        val body = request.responseBody ?: return false
        if (contentMode == HarContentMode.NONE) return false
        if (bodyByteLength(request, body) > MAX_BODY_BYTES) return false
        if (embeddedBytes + bodyByteLength(request, body) > maxTotalBodyBytes) return false
        if (contentMode == HarContentMode.ALL) return true
        // TEXT mode: only text-like MIME types.
        val mimeType = request.mimeType ?: return false
        return TEXT_MIME_PATTERN.containsMatchIn(mimeType)
    }

    private fun embeddedSizeOf(
        request: TrackedNetworkRequest,
        contentMode: HarContentMode,
        maxTotalBodyBytes: Long,
    ): Long {
        if (contentMode == HarContentMode.NONE) return 0L
        val body = request.responseBody ?: return 0L
        val size = bodyByteLength(request, body)
        if (size > MAX_BODY_BYTES) return 0L
        if (contentMode == HarContentMode.TEXT) {
            val mimeType = request.mimeType ?: return 0L
            if (!TEXT_MIME_PATTERN.containsMatchIn(mimeType)) return 0L
        }
        return size
    }

    private fun bodyByteLength(request: TrackedNetworkRequest, body: String?): Long {
        if (body == null) return 0L
        return if (request.responseBodyBase64) {
            runCatching { Base64.getDecoder().decode(body).size.toLong() }.getOrDefault(0L)
        } else {
            body.toByteArray(StandardCharsets.UTF_8).size.toLong()
        }
    }

    private fun httpVersion(protocol: String?): String = when (protocol?.lowercase()) {
        null, "" -> ""
        "h2" -> "HTTP/2.0"
        "http/1.1" -> "HTTP/1.1"
        "http/1.0" -> "HTTP/1.0"
        else -> protocol.uppercase()
    }

    private fun flattenHeaders(headers: Map<String, Any?>): List<Map<String, String>> {
        return headers.entries.mapNotNull { (name, value) ->
            when (value) {
                is List<*> -> value.mapNotNull { it?.toString() }.map { mapOf("name" to name, "value" to it) }
                null -> null
                else -> listOf(mapOf("name" to name, "value" to value.toString()))
            }
        }.flatten()
    }

    private fun firstHeader(headers: Map<String, Any?>, name: String): String? {
        val value = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
        return when (value) {
            is List<*> -> value.firstOrNull()?.toString()
            else -> value.toString()
        }
    }

    private fun parseCookies(header: String?): List<Map<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) {
                mapOf("name" to pair[0].trim(), "value" to pair[1].trim())
            } else null
        }
    }

    private fun parseSetCookies(headers: Map<String, Any?>): List<Map<String, String>> {
        val value = headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value ?: return emptyList()
        val values = when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(value.toString())
        }
        return values.mapNotNull { cookie ->
            val pair = cookie.split(';', limit = 2).first().trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) {
                mapOf("name" to pair[0].trim(), "value" to pair[1].trim())
            } else null
        }
    }

    private fun parseQueryString(url: String): List<Map<String, String>> {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return emptyList()
        val query = url.substring(queryStart + 1).substringBefore('#')
        if (query.isBlank()) return emptyList()
        return query.split('&').mapNotNull { pair ->
            val kv = pair.split('=', limit = 2)
            val name = decodeQueryComponent(kv[0])
            val value = if (kv.size == 2) decodeQueryComponent(kv[1]) else ""
            if (name.isNotBlank()) mapOf("name" to name, "value" to value) else null
        }
    }

    private fun decodeQueryComponent(component: String): String =
        runCatching { URLDecoder.decode(component, StandardCharsets.UTF_8.name()) }.getOrDefault(component)

    private fun deltaMillis(start: Double, end: Double): Long {
        if (start <= 0.0 || end <= 0.0 || end <= start) return -1
        return ((end - start) * 1000.0).roundToLong().coerceAtLeast(1L)
    }

    private fun formatTimestamp(epochMillis: Long): String = HAR_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis))
}
