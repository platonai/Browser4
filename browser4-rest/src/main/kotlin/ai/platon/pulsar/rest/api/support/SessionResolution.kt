package ai.platon.pulsar.rest.api.support

import ai.platon.pulsar.common.B4Constants.DEFAULT_SESSION_ID

/**
 * How a REST request answers "which session does this address?".
 *
 * The *answer* is deliberately not the same on every face, and this file does not try to
 * make it so: the page-scrape face refuses a request that names none — silently picking a
 * session would read another caller's page (decision A2, "open first") — while the command
 * endpoints fall back to [DEFAULT_SESSION_ID]. Both are contracts a caller can rely on.
 *
 * What *is* shared, and what used to be spelled out per call site, is the rule for reading
 * the answer: a session id is a **non-blank** string, so `null`, `""` and `"   "` all mean
 * "this request did not name one". Read literally, `""` is a session named the empty
 * string — a session the caller never opened, addressed by a parameter that looks like it
 * was left out. Every site that says "fall back to the default" has to agree on that, or
 * `?sessionId=` means two different things two routes apart.
 */
object SessionResolution {

    /**
     * The first candidate that names a session, or `null` when none does.
     *
     * Candidates are read left to right, so precedence stays with the caller: the body of a
     * request beats its query parameter.
     *
     * @param candidates the values to read, most specific first.
     * @return the first non-blank value, or `null`.
     */
    fun firstNamed(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    /**
     * [firstNamed], or [DEFAULT_SESSION_ID] when the request named no session.
     *
     * @param candidates the values to read, most specific first.
     * @return the session to operate on; never blank.
     */
    fun firstNamedOrDefault(vararg candidates: String?): String =
        firstNamed(*candidates) ?: DEFAULT_SESSION_ID
}
