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
 *   "same request, no redirect" case.  Query *parameter order* is not part of
 *   that identity: the engine's own normalizer sorts the parameters, so the
 *   identity a fetch is filed under and the address the browser was navigated
 *   to (and reports back as `document.URL`) differ in spelling alone.  A
 *   same-host, same-path, default-port `http→https` 301 upgrade is also treated
 *   as the same document (a downgrade or an explicit port is not).
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

    /** Scheme, host, port, path and query must all match (query order excepted). */
    fun referToSameDocument(a: String, b: String): Boolean {
        if (a == b) return true
        return runCatching {
            val ua = java.net.URI(a.substringBefore('#'))
            val ub = java.net.URI(b.substringBefore('#'))
            val ha = ua.host?.lowercase() ?: return@runCatching false
            val hb = ub.host?.lowercase() ?: return@runCatching false
            val pa = (ua.path ?: "").removeSuffix("/")
            val pb = (ub.path ?: "").removeSuffix("/")
            ha == hb && sameOriginAddress(ua, ub) &&
                pa == pb && referToSameQuery(ua.query, ub.query)
        }.getOrDefault(false)
    }

    /**
     * Whether two query strings carry the same parameters, in any order.
     *
     * Parameter order is not part of a URL's identity, and the engine produces
     * both spellings for one fetch: `FetchTask.url` is the *normalized* identity,
     * whose query the normalizer sorts (`?delayMs=600&links=3`), while the driver
     * is navigated to the document's own *address*, `FetchTask.href`
     * (`?links=3&delayMs=600`) — the rule the emulator states as "normalize for
     * the key, href for the address".  The browser reports the address back
     * verbatim as `document.URL`, and comparing the two literally made the
     * snapshot origin guard refuse the document of its **own** navigation: the
     * driver was retired, the fetch retried as `TabOriginMismatchException`, and
     * the page was lost with empty content (a crawl of such a URL finds 0
     * out-links and reports "Portal page returned near-empty content, 44 bytes").
     *
     * Only the order is ignored — names and values must still match exactly, so a
     * genuinely different request (`?th=1` for a fetch of `?psc=1`) is still
     * refused, which is what keeps the guard meaningful.
     */
    private fun referToSameQuery(a: String?, b: String?): Boolean {
        val qa = a.orEmpty()
        val qb = b.orEmpty()
        if (qa == qb) return true
        if (qa.isEmpty() || qb.isEmpty()) return false
        val pa = qa.split('&')
        val pb = qb.split('&')
        return pa.size == pb.size && pa.sorted() == pb.sorted()
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
            ha == hb && sameOriginAddress(ua, ub) && pa == pb
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

    /**
     * Whether the committed and requested URLs sit on the same origin,
     * tolerating a default-port http→https upgrade.
     *
     * Parameter order mirrors every call site: [committed] is the document the
     * browser actually committed (`document.URL`), [requested] is the URL the
     * fetch asked for.
     *
     * Many sites answer their bare `http://host/` URL with a 301 to the
     * `https://host/` page on the same host and path (books.toscrape.com does).
     * Requiring identical schemes made the guard refuse the very page that was
     * requested, retiring the driver and losing the content. We accept that
     * upgrade ONLY when:
     *  - the schemes are identical and the effective ports match, OR
     *  - the REQUESTED url is plain `http` on its default port (80) and the
     *    COMMITTED url is `https` on its default port (443).
     *
     * The reverse (an https request committing as http — a downgrade), any
     * explicit/non-default port, and any other scheme pair are still refused;
     * host and path are checked by the callers.
     */
    private fun sameOriginAddress(committed: java.net.URI, requested: java.net.URI): Boolean {
        val sa = committed.scheme?.lowercase()
        val sb = requested.scheme?.lowercase()
        if (sa == sb) return effectivePort(committed) == effectivePort(requested)
        // One-directional upgrade only: requested http:80 -> committed https:443.
        return sb == "http" && sa == "https" &&
            requested.port <= 0 && committed.port <= 0
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
