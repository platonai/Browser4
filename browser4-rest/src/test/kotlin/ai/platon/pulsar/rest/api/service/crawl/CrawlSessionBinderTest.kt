package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull

/**
 * Which session a crawl reads the site with is a data-quality decision, not a
 * convenience: cookies (locale, currency, delivery location, login) live in the browser
 * profile, so the wrong profile silently produces a dataset from the wrong storefront.
 */
@DisplayName("CrawlSessionBinder session resolution")
@Tag("Unit")
@Tag("Fast")
class CrawlSessionBinderTest {

    private lateinit var sessionManager: PulsarSessionManager
    private lateinit var binder: CrawlSessionBinder

    @BeforeEach
    fun setUp() {
        sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        binder = CrawlSessionBinder(sessionManager)
    }

    @Test
    @DisplayName("a crawl that names no session reads with the DEFAULT session")
    fun defaultsToTheDefaultSession() {
        // The whole point of the default: a bulk run must not need an extra flag to read
        // the site in the profile the user already browsed and warmed.
        assertEquals(DEFAULT_SESSION_ID, binder.requestedSessionName(CrawlRequest(url = "https://example.com")))
    }

    @Test
    @DisplayName("the calling session is the default when the request names no fetch session")
    fun fallsBackToTheCallingSession() {
        // The MCP layer stamps the session the call was addressed to into `sessionId`,
        // so `crawl -s shop …` reads with `shop` without any crawl-specific flag.
        val request = CrawlRequest(url = "https://example.com", sessionId = "shop")
        assertEquals("shop", binder.requestedSessionName(request))
    }

    @Test
    @DisplayName("an explicit fetchSession wins over the calling session")
    fun explicitFetchSessionWins() {
        val request = CrawlRequest(url = "https://example.com", sessionId = "shop", fetchSession = "amazon-us")
        assertEquals("amazon-us", binder.requestedSessionName(request))
    }

    @Test
    @DisplayName("a blank session name is not a session name")
    fun blankNamesAreIgnored() {
        // A CLI that sends empty strings for absent flags must not turn into a lookup of
        // the session named "".
        val request = CrawlRequest(url = "https://example.com", sessionId = "  ", fetchSession = "")
        assertEquals(DEFAULT_SESSION_ID, binder.requestedSessionName(request))
    }

    @Test
    @DisplayName("a session that cannot be resolved falls back to the pool instead of failing the crawl")
    fun unresolvableSessionDoesNotFailTheCrawl() {
        // Losing the whole task because a profile could not be bound would be worse than
        // reading with the pool: report it and keep crawling.
        Mockito.`when`(sessionManager.getOrCreateSession(any(), anyOrNull()))
            .thenThrow(IllegalStateException("session manager unavailable"))

        val binding = binder.bind("task-1", CrawlRequest(url = "https://example.com", sessionId = DEFAULT_SESSION_ID))

        assertNull(binding, "an unresolvable browser must degrade to the pool, not throw")
    }
}
