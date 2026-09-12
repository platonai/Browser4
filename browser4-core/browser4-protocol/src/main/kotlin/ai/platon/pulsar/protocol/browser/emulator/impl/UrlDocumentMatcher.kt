package ai.platon.pulsar.protocol.browser.emulator.impl

import ai.platon.pulsar.common.urls.URLUtils

/**
 * URL identity checks behind the snapshot origin guard.
 *
 * The guard compares the document the browser actually committed
 * (`document.URL`) with the URL a fetch asked for, and refuses to record
 * content that might belong to a different page.  Three levels of strictness
 * are needed:
 *
 * - [referToSameDocument] — scheme + host + port + path + query.  This is the
 *   "same request, no redirect" case.
 * - [referToSamePageIgnoringQuery] — the same, ignoring the query string.
 *   Sites routinely answer a URL with a 302 that changes only variant or
 *   tracking parameters (Amazon: `…/dp/B0X?psc=1` → `…/dp/B0X?th=1`, and
 *   `/dp/B0X` → `/dp/B0X?th=1`).  The committed document *is* the requested
 *   page, so capturing it is correct; a document from a different path or host
 *   is still rejected.
 * - [referToSameLocalFile] — a local-file fetch asked for a filesystem path and
 *   the committed document is that path's `file://` URL.  The two never match
 *   as URLs, so the fetch URL's encoded path is compared instead.
 */
internal object UrlDocumentMatcher {

    /** Scheme, host, port, path and query must all match. */
    fun referToSameDocument(a: String, b: String): Boolean {
        if (a == b) return true
        return runCatching {
            val ua = java.net.URI(a.substringBefore('#'))
            val ub = java.net.URI(b.substringBefore('#'))
            val ha = ua.host?.lowercase() ?: return@runCatching false
            val hb = ub.host?.lowercase() ?: return@runCatching false
            val pa = (ua.path ?: "").removeSuffix("/")
            val pb = (ub.path ?: "").removeSuffix("/")
            ha == hb && ua.scheme == ub.scheme &&
                effectivePort(ua) == effectivePort(ub) &&
                pa == pb && (ua.query ?: "") == (ub.query ?: "")
        }.getOrDefault(false)
    }

    /** Same as [referToSameDocument] but the query string is irrelevant. */
    fun referToSamePageIgnoringQuery(a: String, b: String): Boolean {
        if (a == b) return true
        return runCatching {
            val ua = java.net.URI(a.substringBefore('#'))
            val ub = java.net.URI(b.substringBefore('#'))
            val ha = ua.host?.lowercase() ?: return@runCatching false
            val hb = ub.host?.lowercase() ?: return@runCatching false
            val pa = (ua.path ?: "").removeSuffix("/")
            val pb = (ub.path ?: "").removeSuffix("/")
            ha == hb && ua.scheme == ub.scheme &&
                effectivePort(ua) == effectivePort(ub) &&
                pa == pb
        }.getOrDefault(false)
    }

    /**
     * Whether the committed document is the local file a local-file fetch asked
     * for.
     *
     * A local-file fetch is served by the driver itself: `PulsarWebDriver`
     * decodes `http://localfile.internal?path=<base64>` to a filesystem path and
     * navigates the tab to that path's `file://` URI.  The committed document
     * therefore never matches the fetch URL, and a `file://` navigation carries
     * no main-document request to confirm it with — the two ways a legitimately
     * committed document is otherwise recognised.  The decoded path is the
     * remaining evidence: the translation is accepted only when the committed
     * document is exactly the file this fetch encoded, never a file:// document
     * from an earlier fetch.
     */
    fun referToSameLocalFile(committedUrl: String, taskUrl: String): Boolean {
        if (!URLUtils.isLocalFile(taskUrl)) return false
        if (!committedUrl.startsWith("file:")) return false
        val requested = runCatching { URLUtils.localURLToPath(taskUrl) }.getOrNull() ?: return false
        val committed = runCatching { java.nio.file.Path.of(java.net.URI(committedUrl)) }.getOrNull()
            ?: return false
        return requested.normalize() == committed.normalize()
    }

    private fun effectivePort(uri: java.net.URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }
}
