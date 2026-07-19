package ai.platon.pulsar.agentic.tools.experience

import java.net.URI

/**
 * URL normalization and pattern matching for the learning system.
 *
 * Two-layer approach from the PEM v2 proposal:
 * 1. Global rules: strip query params (except semantically significant ones),
 *    normalize trailing slashes and www. prefix, strip fragments.
 * 2. Pattern matching: regex-based wildcard resolution with specificity scoring.
 *
 * URL patterns like /dp/{asterisk} match concrete URLs like /dp/B0CXJ1NT4B.
 * Specificity is measured by counting literal (non-wildcard) path segments.
 */
object UrlNormalizer {

    /**
     * Query parameter keys that are semantically significant and preserved
     * during normalization. These are the parameters whose presence defines
     * a page type (e.g., ?k={asterisk} on amazon.com/s distinguishes search-result
     * pages from product-detail pages).
     * In Phase 5, this set becomes configurable per site from learned data.
     */
    val SEMANTICALLY_SIGNIFICANT_PARAMS: MutableSet<String> = mutableSetOf(
        "q",     // search query
        "id",    // resource identifier
        "page",  // pagination
        "k",     // Amazon keyword search
    )

    /**
     * Normalize a concrete URL for storage and pattern matching.
     *
     * Applies global rules:
     * 1. Parse with java.net.URI
     * 2. Strip www. prefix from host
     * 3. Strip trailing slash from path
     * 4. Strip fragment (#section)
     * 5. Strip query parameters not in [SEMANTICALLY_SIGNIFICANT_PARAMS]
     *
     * @param url The raw URL from a task trace or query request.
     * @return Normalized URL (path + preserved query string).
     *
     * Example:
     * Input:  https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop&qid=123#reviews
     * Output: amazon.com/dp/B0CXJ1NT4B?k=laptop
     */
    fun normalize(url: String): String {
        return try {
            val uri = URI(url.trim())
            val host = uri.host?.removePrefix("www.") ?: ""
            val path = uri.path?.trimEnd('/') ?: "/"

            val preservedParams = uri.query?.let { query ->
                val pairs = query.split("&")
                    .map { it.split("=", limit = 2) }
                    .filter { it.isNotEmpty() && it[0] in SEMANTICALLY_SIGNIFICANT_PARAMS }
                    .map { if (it.size == 2) "${it[0]}=${it[1]}" else it[0] }
                if (pairs.isNotEmpty()) pairs.joinToString("&") else null
            }

            val normalizedPath = if (path.isBlank()) "/" else path
            val result = "$host$normalizedPath"
            if (preservedParams != null) "$result?$preservedParams" else result
        } catch (_: Exception) {
            // Fall back to simple string manipulation for malformed URLs
            url.trim().removePrefix("https://").removePrefix("http://")
                .removePrefix("www.")
                .substringBefore('#')
                .trimEnd('/')
        }
    }

    /**
     * Extract the domain portion from a URL or normalized URL.
     */
    fun extractDomain(url: String): String {
        val normalized = if (url.contains("://")) url else "https://$url"
        return try {
            URI(normalized).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url.substringBefore('/').substringBefore('?').substringBefore('#')
        }
    }

    /**
     * Extract the path portion from a URL.
     *
     * Handles three forms:
     * - Full URL: "https://amazon.com/dp/test" → "/dp/test"
     * - Host + path: "amazon.com/dp/test" → "/dp/test"
     * - Path only: "/dp/test" → "/dp/test"
     */
    fun extractPath(url: String): String {
        // Fast-path: URL is already a path
        if (url.startsWith("/") && !url.contains("://") && !url.contains(".")) {
            return url.trimEnd('/')
        }

        val normalized = if (url.contains("://")) url else "https://$url"
        return try {
            URI(normalized).rawPath?.trimEnd('/')?.ifBlank { "/" } ?: "/"
        } catch (_: Exception) {
            val noScheme = url.substringAfter("://")
            val hostEnd = noScheme.indexOf('/')
            if (hostEnd >= 0) noScheme.substring(hostEnd) else "/"
        }
    }

    /**
     * Convert a URL pattern with wildcards into a regex for matching.
     *
     * The asterisk wildcard matches a single path segment.
     * Literal segments are directly compared.
     */
    fun patternToRegex(pattern: String): Regex {
        val escaped = Regex.escape(pattern)
            .replace("\\*", "[^/?#]+")
        return Regex(escaped)
    }

    /**
     * Check if a concrete URL matches a URL pattern.
     *
     * Uses segment-by-segment comparison with wildcard support.
     *
     * @param pattern URL pattern with wildcards (e.g., /dp/{asterisk}).
     * @param url A concrete URL (may be raw or normalized).
     * @return true if the URL matches the pattern.
     */
    fun matches(pattern: String, url: String): Boolean {
        val path = extractPath(url)
        val patternSegments = pattern.trim('/').split('/')
        val pathSegments = path.trim('/').split('/')

        if (patternSegments.size != pathSegments.size) {
            // Wildcard in query position
            if (pattern.contains("?") && path.contains("?")) {
                return matchWithQuery(pattern, path)
            }
            return false
        }

        for (i in patternSegments.indices) {
            val pSeg = patternSegments[i]
            val uSeg = pathSegments[i]
            if (pSeg == "*") continue  // wildcard matches anything
            // Handle query string in segment
            if (pSeg.contains("?")) {
                return matchWithQuery(pattern, path)
            }
            if (pSeg != uSeg) return false
        }

        return true
    }

    /**
     * Match pattern and URL where both contain query strings.
     */
    private fun matchWithQuery(pattern: String, path: String): Boolean {
        val patternParts = pattern.split("?", limit = 2)
        val pathParts = path.split("?", limit = 2)
        if (patternParts.size != 2 || pathParts.size != 2) return false

        val patternPath = patternParts[0]
        val patternQuery = patternParts[1]
        val urlPath = pathParts[0]
        val urlQuery = pathParts[1]

        // Match path portion
        val pathMatch = matches(patternPath, urlPath)

        // Match query portion: "k=*" matches "k=laptop"
        val queryMatch = if (patternQuery.contains("=")) {
            val pparts = patternQuery.split("=", limit = 2)
            val uparts = urlQuery.split("=", limit = 2)
            pparts.size == 2 && uparts.size == 2 &&
                pparts[0] == uparts[0] &&
                (pparts[1] == "*" || pparts[1] == uparts[1])
        } else {
            patternQuery == "*" || patternQuery == urlQuery
        }

        return pathMatch && queryMatch
    }

    /**
     * Count literal (non-wildcard) path segments in a URL pattern.
     *
     * Higher specificity = more precise match. Used for tie-breaking
     * when multiple patterns match the same URL.
     *
     * Examples:
     * - {asterisk} -> specificity 0
     * - /dp/{asterisk} -> specificity 1
     * - /s?k={asterisk} -> specificity 1 (query wildcard not counted)
     *
     * @param pattern A URL pattern with optional wildcards.
     * @return Number of literal path segments.
     */
    fun specificity(pattern: String): Int {
        val pathOnly = pattern.substringBefore('?')
        val segments = pathOnly.split('/').filter { it.isNotEmpty() }
        return segments.count { it != "*" }
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
}
