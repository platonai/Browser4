package ai.platon.pulsar.protocol.browser.emulator.impl

/**
 * URL identity checks behind the snapshot origin guard.
 *
 * The guard compares the document the browser actually committed
 * (`document.URL`) with the URL a fetch asked for, and refuses to record
 * content that might belong to a different page.  Two levels of strictness are
 * needed:
 *
 * - [referToSameDocument] — scheme + host + port + path + query.  This is the
 *   "same request, no redirect" case.
 * - [referToSamePageIgnoringQuery] — the same, ignoring the query string.
 *   Sites routinely answer a URL with a 302 that changes only variant or
 *   tracking parameters (Amazon: `…/dp/B0X?psc=1` → `…/dp/B0X?th=1`, and
 *   `/dp/B0X` → `/dp/B0X?th=1`).  The committed document *is* the requested
 *   page, so capturing it is correct; a document from a different path or host
 *   is still rejected.
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

    private fun effectivePort(uri: java.net.URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }
}
