package ai.platon.pulsar.rest.session

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Container for session-related objects.
 *
 * The driverMutex ensures that WebDriver operations are executed serially, not in parallel.
 * This is critical because WebDriver methods must not be called concurrently.
 *
 * @property sessionId The REST-level session id, which is distinct with [AgenticSession.uuid]
 * @property agenticSession The managed [AgenticSession]
 * @property capabilities The capabilities used to create the [AgenticSession]
 */
data class ManagedSession(
    val sessionId: String,
    val agenticSession: AgenticSession,
    val capabilities: Map<String, String?>?,
    var url: String? = null,
    var status: SessionStatus = SessionStatus.ACTIVE,
    /** Explicit session kind — drives ownership and lifecycle decisions. */
    val kind: SessionKind = SessionKind.BROWSER4_LAUNCHED,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
    /** The browser channel the user REQUESTED when attaching (e.g. "msedge"). */
    var attachChannel: String? = null,
    /** Identity of the browser that ACTUALLY connected (extension relay / CDP). */
    var browserIdentity: BrowserIdentity? = null,
) {
    val mutex: Mutex = Mutex()

    val driver get() = agenticSession.getOrCreateBoundDriver()
    val agent: PerceptiveAgent get() = agenticSession.companionAgent

    /** Convenience: does this session own its browser lifecycle? */
    val ownsBrowser: Boolean get() = kind.ownsBrowser

    suspend inline fun <R> withLock(block: ManagedSession.() -> R): R {
        return mutex.withLock(null) {
            this.block()
        }
    }
}

/**
 * Identity of the real browser behind an attached session.
 *
 * For extension-attached sessions this is derived from the WebSocket handshake
 * User-Agent (Chrome vs Edge vs other Chromium brands — the Origin header
 * cannot distinguish them because the extension id is identical across
 * browsers).  For CDP-attached sessions it comes from `/json/version`'s
 * `Browser` product string.  Note a User-Agent can be spoofed; this metadata
 * guards against *accidental* wrong-browser attachment, not malicious
 * impersonation.
 *
 * @param family Normalized family: `edge` (Edg/EdgA/EdgiOS), `chrome`
 *   (Chrome/Chromium), `chromium-other` (Brave/Opera/Vivaldi & co — UA carries
 *   Chrome but no Chromium marker), or `unknown`.
 * @param name Human-readable browser name when derivable (e.g. "Microsoft Edge").
 * @param version Browser version when derivable (e.g. "138.0.0.0"), else null.
 * @param rawUa The raw User-Agent / Browser string observed at connect time.
 */
data class BrowserIdentity(
    val family: String,
    val name: String? = null,
    val version: String? = null,
    val rawUa: String? = null,
) {
    companion object {
        /**
         * Parse a browser User-Agent / product string into a [BrowserIdentity].
         *
         * Edge (Chromium) UAs contain Chrome AND Safari tokens plus an `Edg/`
         * token, so Edge-family markers must be checked FIRST — otherwise every
         * Edge would be classified as Chrome.  Android Edge is `EdgA/`, iOS
         * Edge is `EdgiOS/`; the legacy EdgeHTML UA used a plain `Edge/`.
         * Chromium forks without an Edge marker (Brave/Opera/Vivaldi) carry
         * `Chrome/` and are conservatively bucketed as `chromium-other`.
         */
        fun parse(raw: String?): BrowserIdentity {
            val ua = raw?.trim().orEmpty()
            if (ua.isEmpty()) return BrowserIdentity("unknown", rawUa = null)
            return when {
                // Edge (Chromium) UA: "... Chrome/138 ... Edg/138"; Android = EdgA/, iOS = EdgiOS/.
                Regex("""Edg(?:A|iOS)?/""", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> {
                    BrowserIdentity(
                        "edge", "Microsoft Edge",
                        extractVersion(ua, Regex("""Edg(?:A|iOS)?/([\d.]+)""", RegexOption.IGNORE_CASE)),
                        ua
                    )
                }
                Regex("""Brave/""", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> {
                    BrowserIdentity("chromium-other", "Brave", extractVersion(ua, Regex("""Chrome/([\d.]+)""")), ua)
                }
                Regex("""OPR/|Opera/""", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> {
                    BrowserIdentity("chromium-other", "Opera", extractVersion(ua, Regex("""(?:OPR|Chrome)/([\d.]+)""")), ua)
                }
                Regex("""Vivaldi/""", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> {
                    BrowserIdentity("chromium-other", "Vivaldi", extractVersion(ua, Regex("""Chrome/([\d.]+)""")), ua)
                }
                Regex("""Chrome/|Chromium/""").containsMatchIn(ua) -> {
                    BrowserIdentity("chrome", "Google Chrome", extractVersion(ua, Regex("""Chrome/([\d.]+)""")), ua)
                }
                else -> BrowserIdentity("unknown", rawUa = ua)
            }
        }

        private fun extractVersion(ua: String, pattern: Regex): String? =
            pattern.find(ua)?.groupValues?.getOrNull(1)
    }
}
