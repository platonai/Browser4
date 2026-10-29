package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.slf4j.LoggerFactory

/**
 * The browser a crawl's pages are fetched with, and who owns it.
 *
 * A crawl fetches through [session], and the `Browser` bean carried by that
 * session's config is what the fetch layer resolves first (see
 * `PrivacyManagedBrowserFetcher.getSpecifiedBrowser`): every page load leases a
 * tab from *that* browser, so the crawl reads the site with the profile the
 * caller asked for — its cookies, its locale, its delivery location — and the
 * tabs are still independent, so `--parallel` keeps working.
 *
 * @param session the session the rounds load through; it is only ever used for
 *   its config, and it never holds the caller's driver
 * @param browser the browser the loads are pinned to, or null when it cannot be
 *   resolved (the caller then keeps the historical pool-driven behaviour)
 * @param owned whether this binding created the session *and* the browser; an
 *   owned binding is closed when the crawl finishes, a borrowed one is left
 *   exactly as the caller had it
 * @param description a one-line, human-readable account of what was bound, for
 *   the crawl's log
 */
internal data class CrawlSessionBinding(
    val session: PulsarSession,
    val browser: Browser?,
    val owned: Boolean,
    val description: String,
)

/**
 * Resolves the browser context a crawl runs in.
 *
 * Three modes, in the order [CrawlRequest] documents them:
 *
 *  * `profile = "<user data dir>"` — launch a browser on that directory, owned by
 *    the crawl and closed with it;
 *  * `sessionId = "<name>"` — borrow the browser of an existing session (a named
 *    session, or the DEFAULT session — which is also the default when neither
 *    field is set), so a crawl reads the site with the profile the user already
 *    browsed and warmed;
 *  * neither — the same as `sessionId = DEFAULT`.
 *
 * The borrowed browser is **not** taken over: the crawl gets its own session that
 * carries nothing but the `Browser` bean, so the caller's session keeps its tab,
 * its URL and its bound driver, and the crawl cannot close it.  Only an owned
 * binding is released by the caller.
 */
internal class CrawlSessionBinder(
    private val sessionManager: PulsarSessionManager,
) {
    private val logger = LoggerFactory.getLogger(CrawlSessionBinder::class.java)

    /**
     * Bind the browser this crawl should fetch with.
     *
     * @return the binding, or null when it cannot be resolved (no browser could
     *   be launched for the requested profile/session); the crawl then falls back
     *   to the historical pool-driven fetch instead of failing the whole task
     */
    fun bind(taskId: String, request: CrawlRequest): CrawlSessionBinding? {
        val profile = request.profile?.trim()?.takeIf { it.isNotEmpty() }
        val requestedSession = requestedSessionName(request)

        return try {
            if (profile != null) {
                bindPinnedProfile(taskId, profile)
            } else {
                bindSession(taskId, requestedSession)
            }
        } catch (e: Exception) {
            logger.warn(
                "Crawl {}: could not bind a browser for profile='{}' session='{}' ({}); " +
                    "falling back to the driver pool",
                taskId, profile, requestedSession, e.message
            )
            null
        }
    }

    /**
     * Launch a browser on an explicit Chrome user-data directory and load through
     * it.  The crawl owns both the session and the browser, so both are closed
     * when the crawl finishes.
     */
    private fun bindPinnedProfile(taskId: String, profile: String): CrawlSessionBinding {
        val session = sessionManager.agenticContext.createSession(
            PulsarSettings(label = crawlSessionLabel(taskId), profilePath = profile)
        )
        // The driver is only the handle that launches the browser on `profile`;
        // it is unbound again so the loads lease independent tabs from the browser
        // instead of queueing on one tab.
        val driver = session.createBoundDriver()
        val browser = driver.browser
        session.unbindDriver(driver)

        logger.info(
            "Crawl {}: fetching through profile '{}' (browser id {}, context dir {})",
            taskId, profile, browser.id, runCatching { browser.id.profile.contextDir }.getOrNull()
        )

        return CrawlSessionBinding(
            session = session,
            browser = browser,
            owned = true,
            description = "profile $profile"
        )
    }

    /**
     * Which session the crawl reads the site with.
     *
     * Precedence: the explicit `fetchSession`, then the session the request was
     * addressed to (the MCP layer stamps it into `sessionId`), then the DEFAULT
     * session — so "a crawl reads the site with DEFAULT unless it is told otherwise"
     * is a property of this function, not of every caller.
     */
    internal fun requestedSessionName(request: CrawlRequest): String =
        listOf(request.fetchSession, request.sessionId)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?: DEFAULT_SESSION_ID

    /**
     * Borrow the browser of [sessionId] (DEFAULT when the crawl did not name one).
     *
     * The session is resolved through [PulsarSessionManager], so a named session
     * keeps its dedicated profile directory and the DEFAULT session is the same
     * one `open`/`goto` drove.
     */
    private fun bindSession(taskId: String, sessionId: String): CrawlSessionBinding {
        val managed = sessionManager.getOrCreateSession(sessionId)
        val target = managed.agenticSession
        val browser = target.boundBrowser ?: target.getOrCreateBoundDriver().browser

        // The crawl's own session carries the browser bean and nothing else: no
        // driver, so concurrent loads lease their own tabs; and the bean is put
        // directly (not via bindBrowser) so closing this session cannot close a
        // browser the caller still owns.
        val session = sessionManager.agenticContext.createSession(
            PulsarSettings(label = crawlSessionLabel(taskId))
        )
        session.sessionConfig.putBean(browser)

        logger.info(
            "Crawl {}: fetching through session '{}' (browser id {}, context dir {})",
            taskId, sessionId, browser.id, runCatching { browser.id.profile.contextDir }.getOrNull()
        )

        return CrawlSessionBinding(
            session = session,
            browser = browser,
            owned = false,
            description = "session $sessionId"
        )
    }
}
