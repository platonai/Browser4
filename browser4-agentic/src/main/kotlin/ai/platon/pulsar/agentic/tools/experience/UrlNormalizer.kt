package ai.platon.pulsar.agentic.tools.experience

import java.net.IDN
import java.net.URI

/**
 * URL normalization and pattern matching for the learning system.
 *
 * Two-layer approach from the PEM v2 proposal:
 * 1. Global rules: strip query params (except semantically significant ones),
 *    normalize trailing slashes and www. prefix, strip fragments.
 * 2. Pattern matching: wildcard resolution with specificity scoring.
 *
 * URL patterns like `/dp/{asterisk}` match concrete URLs like `/dp/B0CXJ1NT4B`.
 * Specificity is measured by counting literal (non-wildcard) path segments.
 *
 * The three functions have to agree on one spelling or the store and the matcher disagree with each
 * other.  The contract is:
 *
 *  * [normalize] turns a concrete url into `host/path[?significant-query]` with no scheme,
 *  * [urlPatternOf] turns that into the pattern shape stored in a fact (`/dp/{asterisk}`, `/s?k=*`),
 *  * [matches] answers whether a stored pattern covers a url, in both spellings.
 *
 * Before, [urlPatternOf]'s two private copies walked the *path* ([extractPath]) while the pattern's
 * query was preserved by [normalize] — so a preserved `?k=` was thrown away by the producer and the
 * documented `/s?k=*` pattern could never be produced, nor matched against a `host/path` url.
 */
object UrlNormalizer {

    /**
     * Query parameter keys that are semantically significant and preserved
     * during normalization. These are the parameters whose presence defines
     * a page type (e.g., ?k=* on amazon.com/s distinguishes search-result
     * pages from product-detail pages).
     *
     * Immutable on purpose: it is read on every [normalize] call, and the
     * "configurable per site" phase has not been designed yet.  A mutable
     * process-global set with no happens-before edge is a data race waiting
     * for its first writer.
     */
    val SEMANTICALLY_SIGNIFICANT_PARAMS: Set<String> = setOf(
        "q",     // search query
        "id",    // resource identifier
        "page",  // pagination
        "k",     // Amazon keyword search
    )

    /**
     * The keys of a `key=value` *path* segment that is a tracking marker rather than a resource.
     *
     * Amazon — and every site that copied its urls — spells its click tracking inside the path
     * (`/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop`).  A segment containing the first `=` is not part of the
     * resource, and leaving it in gave one page two patterns (`/dp/{asterisk}` and
     * `/dp/B0CXJ1NT4B/ref=sr_1_1`), so which knowledge was retrieved depended on the last writer.
     *
     * The key has to be *known* to be dropped: a base64-padded id (`.../abc==`) also contains an
     * `=`, and silently folding it away would merge two real resources.
     */
    private val TRACKING_PATH_KEYS = setOf(
        "ref", "qid", "sr", "keywords", "th", "psc", "smid", "sprefix", "crid", "coliid", "colid",
    )

    /** The prefixes that mark a tracking key (`ref_=`, `pf_rd_p=`, `pd_rd_i=`). */
    private val TRACKING_PATH_KEY_PREFIXES = listOf("ref_", "pf_rd_", "pd_rd_")

    /**
     * The domain a url is filed under when it has none that can be parsed.  A fact is stored in a
     * directory named after its domain, so this has to be a name a filesystem accepts; the query
     * entry point does not validate its url (it is fed from free text, see `MemoryRecallService`).
     */
    const val UNKNOWN_DOMAIN = "unknown"

    /**
     * Normalize a concrete URL for storage and pattern matching.
     *
     * Applies global rules:
     * 1. Assume `https` when the url carries no scheme
     * 2. Strip the `www.` prefix, and ASCII-fold the host (`中文.cn` -> `xn--fiq228c.cn`)
     * 3. Drop tracking path segments (`/dp/X/ref=sr_1_1` -> `/dp/X`)
     * 4. Strip the trailing slash of the path
     * 5. Strip the fragment (`#section`)
     * 6. Keep only the query parameters in [SEMANTICALLY_SIGNIFICANT_PARAMS], keys lowercased
     *
     * The path and the query keep their case — both are case sensitive on most servers, so folding
     * them would invent a collision no server would agree with.
     *
     * Idempotent: `normalize(normalize(url)) == normalize(url)`, which is what lets a pattern
     * (produced from a normalized url) be matched against a normalized url.
     *
     * @param url The raw URL from a task trace or query request.
     * @return Normalized URL (`host/path[?query]`), without a scheme.
     *
     * Example:
     * Input:  https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop&qid=123#reviews
     * Output: amazon.com/dp/B0CXJ1NT4B?k=laptop
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        return try {
            val uri = URI(withScheme)
            val host = safeHost(uri.host ?: "").removePrefix("www.")
            val path = dropTrackingSegments(uri.rawPath ?: "").trimEnd('/')
            val query = significantQuery(uri.rawQuery)

            val base = "$host${path.ifBlank { "/" }}"
            if (query.isEmpty()) base else "$base?$query"
        } catch (_: Exception) {
            // A url `URI` refuses (a raw space, a stray bracket).  The fallback produces the *same
            // shape* as the happy path -- no scheme, no fragment, no query -- instead of the old
            // string surgery that kept the query and could return the whole raw url, which then
            // became a directory name.
            fallbackNormalize(trimmed)
        }
    }

    /**
     * Extract the domain portion from a URL or normalized URL.
     *
     * The host is lowercased and ASCII-folded, because it is both the display label and the
     * *directory name* a fact lives in: `WWW.Amazon.com` and `amazon.com` used to be two stores,
     * and an internationalised host made `URI.host` null, which returned the whole raw url as the
     * directory name.
     *
     * @return the host, or [UNKNOWN_DOMAIN] when the input has nothing host-shaped in it.
     */
    fun extractDomain(url: String): String {
        val raw = url.trim()
        val withScheme = if (raw.contains("://")) raw else "https://$raw"

        val host = runCatching { URI(withScheme).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            val safe = safeHost(host).removePrefix("www.")
            if (safe.isNotBlank()) {
                return safe
            }
        }

        // Not a parseable url (a bare word, a query-only string, an opaque scheme, `../x`).  Reduce
        // it to characters a host may contain rather than returning it verbatim.
        val withoutScheme = raw.substringAfter("://")
        val token = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return safeHost(token).removePrefix("www.").ifBlank { UNKNOWN_DOMAIN }
    }

    /**
     * Extract the path portion from a URL.
     *
     * Handles three forms:
     * - Full URL: "https://amazon.com/dp/test" → "/dp/test"
     * - Host + path: "amazon.com/dp/test" → "/dp/test"
     * - Path only: "/dp/test" → "/dp/test"
     */
    fun extractPath(url: String): String = pathOf(url).ifBlank { "/" }

    /**
     * The pattern shape of a url: literal segments kept, the last segment wildcarded when it looks
     * like an id, and the preserved query kept with its *values* wildcarded.
     *
     * `https://amazon.com/dp/B0CXJ1NT4B?k=laptop&qid=1` -> `/dp/{asterisk}?k=*`, `amazon.com` -> `/`
     *
     * The url is normalized first, so any spelling may be passed and a raw url cannot smuggle a
     * non-significant query into the pattern.  The pattern is derived here so the producer and the
     * matcher cannot drift apart: the tool executor and the knowledge provider used to carry their
     * own copy of this logic.
     */
    fun urlPatternOf(url: String): String {
        val normalized = normalize(url)

        val segments = pathOf(normalized).split('/').filter { it.isNotEmpty() }

        val pathPattern = if (segments.isEmpty()) {
            // The root. It is an exact pattern rather than `/{asterisk}`: a wildcard has to match at
            // least one
            // segment (see matchesPath), and a site-wide pattern that also matched the root would be
            // a different thing from "this site's home page".
            "/"
        } else {
            val last = segments.last()
            if (isLikelyId(last)) "/" + segments.dropLast(1).joinToString("/") + "/*"
            else "/" + segments.joinToString("/")
        }

        val query = queryOf(normalized)
        if (query.isEmpty()) {
            return pathPattern
        }

        val values = queryPairs(query).entries
            .joinToString("&") { entry -> "${entry.key}=${if (entry.value.isEmpty()) "" else "*"}" }
        return if (values.isEmpty()) pathPattern else "$pathPattern?$values"
    }

    /**
     * Convert a URL pattern with wildcards into a regex for matching.
     *
     * The asterisk wildcard matches a single path segment.
     */
    fun patternToRegex(pattern: String): Regex {
        val escaped = Regex.escape(pattern)
            .replace("\\*", "[^/?#]+")
        return Regex(escaped)
    }

    /**
     * Check if a concrete URL matches a URL pattern.
     *
     * `*` matches exactly one path segment, except as the *last* segment of a pattern, where it
     * stands for the rest of the path — that is what makes `/dp/{asterisk}` and the site-wide
     * `/{asterisk}` a homepage records work on real urls. A trailing `*` still requires at least one
     * segment, so `/dp/{asterisk}` does not match the listing page `/dp/`; `/{asterisk}` on its own
     * is the one exception, because it is the
     * catch-all that has to match the root too.
     *
     * A pattern that names a query parameter constrains it (a `*` value accepts anything); a pattern
     * without a query accepts any query on that path, so patterns written before the query became
     * part of the identity still match.
     *
     * Both a raw url (`https://amazon.com/s?k=laptop`, `amazon.com/s?k=laptop`) and a normalized one
     * (`amazon.com/s?k=laptop`) are accepted.
     */
    fun matches(pattern: String, url: String): Boolean {
        val patternPath = pathOf(pattern)
        val urlPath = pathOf(url)

        if (!matchesPath(patternPath, urlPath)) {
            return false
        }

        val patternQuery = queryOf(pattern)
        if (patternQuery.isEmpty()) {
            return true
        }

        return matchesQuery(patternQuery, queryOf(url))
    }

    /**
     * Count the literal (non-wildcard) path segments in a URL pattern, plus one when the pattern also
     * pins the query shape.
     *
     * Higher specificity = more precise match. Used for tie-breaking when multiple patterns match the
     * same URL. Counting the query matters: without it `/s` and `/s?k=*` score the same and the
     * winner is whatever order the directory listing came back in.
     *
     * Examples:
     * - `/{asterisk}` -> specificity 0
     * - `/dp/{asterisk}` -> specificity 1
     * - `/s?k=*` -> specificity 2 (one literal segment, plus the query shape)
     *
     * @param pattern A URL pattern with optional wildcards.
     * @return The specificity score.
     */
    fun specificity(pattern: String): Int {
        val literals = pathOf(pattern).split('/').count { it.isNotEmpty() && it != "*" }
        return literals + if (queryOf(pattern).isEmpty()) 0 else 1
    }

    /**
     * Find the most specific matching URL pattern from a list of candidates.
     *
     * When multiple patterns match, the one with the highest specificity wins.
     *
     * @param url The concrete URL to match.
     * @param patterns List of candidate URL patterns (with wildcards).
     * @return The best matching pattern, or null if none match.
     */
    fun findBestMatch(url: String, patterns: List<String>): String? {
        return patterns
            .filter { matches(it, url) }
            .maxByOrNull { specificity(it) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * The path of a url in any of its spellings (`https://h/p?q`, `h/p?q`, `/p?q`), without the
     * query or the fragment.
     */
    private fun pathOf(url: String): String {
        val withoutFragment = url.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val withoutScheme = withoutQuery.substringAfter("://")
        val slash = withoutScheme.indexOf('/')
        return if (slash < 0) "" else withoutScheme.substring(slash).trimEnd('/')
    }

    /** The query of a url in any of its spellings, without the fragment. */
    private fun queryOf(url: String): String = url.substringBefore('#').substringAfter('?', "")

    private fun matchesPath(patternPath: String, urlPath: String): Boolean {
        val patternSegments = patternPath.split('/').filter { it.isNotEmpty() }
        val urlSegments = urlPath.split('/').filter { it.isNotEmpty() }

        if (patternSegments.isEmpty()) {
            return urlSegments.isEmpty()
        }

        for (i in patternSegments.indices) {
            val p = patternSegments[i]
            if (p == "*") {
                if (i == patternSegments.lastIndex) {
                    // The catch-all `/{asterisk}` is the site-wide pattern a homepage records: it
                    // accepts the
                    // root as well as any path. Any other trailing `*` means "a page below here".
                    return if (patternSegments.size == 1) true else i < urlSegments.size
                }
                continue
            }
            if (i >= urlSegments.size || p != urlSegments[i]) {
                return false
            }
        }
        return patternSegments.size == urlSegments.size
    }

    private fun matchesQuery(patternQuery: String, urlQuery: String): Boolean {
        if (patternQuery == "*") {
            return true
        }

        val urlPairs = queryPairs(urlQuery)
        // The pattern constrains the parameters it names; the url may carry others, because the ones
        // that are not significant were removed from it before it was matched.
        return queryPairs(patternQuery).all { (key, value) ->
            urlPairs[key]?.let { value == "*" || value == it } ?: false
        }
    }

    /** The `key=value` pairs of a raw (still encoded) query string, keys lowercased. */
    private fun queryPairs(query: String): Map<String, String> {
        return query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=').lowercase()
                key to pair.substringAfter('=', "")
            }
    }

    /** Keep the significant parameters of a raw query, in their original order. */
    private fun significantQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) {
            return ""
        }

        // Split the *raw* query: decoding first turned an encoded `&` inside a value (`?k=a%26b`)
        // into a pair separator and truncated the value.
        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').lowercase()
                if (key !in SEMANTICALLY_SIGNIFICANT_PARAMS) null
                else key to pair.substringAfter('=', "")
            }
            .joinToString("&") { entry -> if (entry.second.isEmpty()) entry.first else "${entry.first}=${entry.second}" }
    }

    /** Drop the `key=value` tracking segments a site spells inside the path. */
    private fun dropTrackingSegments(rawPath: String): String {
        if (rawPath.isEmpty() || !rawPath.contains('=')) {
            return rawPath
        }

        val kept = rawPath.split('/').filter { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) {
                true
            } else {
                val key = segment.substring(0, eq).lowercase()
                key !in TRACKING_PATH_KEYS && TRACKING_PATH_KEY_PREFIXES.none { key.startsWith(it) }
            }
        }

        return kept.joinToString("/")
    }

    private fun isLikelyId(segment: String): Boolean =
        segment.any { it.isDigit() } && segment.length > 4 && !segment.all { it.isLetter() }

    /**
     * A host reduced to what may appear in a host *and* in a directory name: lowercased, ASCII-folded
     * for internationalised names, and stripped of anything that could escape a path (`..`, `/`).
     */
    private fun safeHost(host: String): String {
        val ascii = runCatching { IDN.toASCII(host) }.getOrDefault(host)
        return ascii.lowercase()
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' || it == '[' || it == ']' }
            .trim('.')
    }

    /** The same shape as the happy path, for a url `URI` refuses to parse. */
    private fun fallbackNormalize(url: String): String {
        val head = url.substringBefore('#').substringBefore('?')
        val withoutScheme = head.substringAfter("://")
        val slash = withoutScheme.indexOf('/')
        val host = (if (slash < 0) withoutScheme else withoutScheme.substring(0, slash))
            .lowercase()
            .removePrefix("www.")
        val path = if (slash < 0) "" else withoutScheme.substring(slash).trimEnd('/')
        return "$host${path.ifBlank { if (host.isEmpty()) "" else "/" }}"
    }
}
