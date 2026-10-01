package ai.platon.pulsar.skeleton.common.urls

import ai.platon.pulsar.common.urls.URLUtils

/**
 * The [URLUtils.normalize] entry points that are safe against a *discarded* fragment.
 *
 * `URLUtils.normalize` drops the fragment and the trailing argument list, but it used to build
 * the whole uri first — so an invalid escape or a second sharp inside the fragment it was about
 * to throw away made the entire url unparseable:
 *
 * ```
 * https://example.com/a#100%               -> null   (Malformed escape pair)
 * https://example.com/a#x#y                -> null   (Illegal character in fragment)
 * http://example.com/!@#$%^&*()            -> null
 * ```
 *
 * Every browser loads all three, and okhttp agrees: `URLUtils.isStandard` returns `true` for
 * them, so the two validity gates of this codebase used to contradict each other — a url could
 * pass `isStandard` (accepted as a discovered href, a crawl seed, a command) and then normalize
 * to NIL, which the load path turns into a `GoraWebPage.NIL` with nothing but an `info` log.
 *
 * The fragment is split off *before* the url is handed to [URLUtils], so the part that survives
 * the normalization is the only part that has to be well formed. The argument list is split off
 * first as well, so a `#` inside an option value (`-requireNotBlank '#productTitle'`) is never
 * mistaken for a fragment.
 *
 * TODO: collapse this into [URLUtils.normalize] and delete this file once browser4 consumes a
 *   pulsar-common release that contains the fix (see `browser4-base.version` in the root pom).
 *   The upstream fix was made on branch `fix/urlutils-normalize-fragment-safe` of browser4base.
 */
object SafeUrlNormalize {

    /**
     * Normalize a configured url, tolerating a fragment that [URLUtils.normalize] would reject.
     *
     * @param configuredUrl the url, optionally followed by a whitespace-separated argument list
     *   (the argument list is removed, exactly as [URLUtils.normalize] does)
     * @param ignoreQuery drop the query string as well; only pass `true` where the query is
     *   genuinely not part of the resource identity
     * @return the normalized url, or null when the *kept* part of the url is invalid
     */
    fun normalizeOrNull(configuredUrl: String?, ignoreQuery: Boolean = false): String? {
        if (configuredUrl == null) {
            return null
        }

        val (url, _) = URLUtils.splitUrlArgs(configuredUrl)
        return URLUtils.normalizeOrNull(url.substringBefore('#'), ignoreQuery)
    }

    /**
     * Normalize a configured url, returning an empty string when the url has no normal form.
     *
     * @see normalizeOrNull
     */
    fun normalizeOrEmpty(configuredUrl: String?, ignoreQuery: Boolean = false): String =
        normalizeOrNull(configuredUrl, ignoreQuery) ?: ""
}
