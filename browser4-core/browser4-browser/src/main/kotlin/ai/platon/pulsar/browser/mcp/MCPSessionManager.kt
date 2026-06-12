package ai.platon.pulsar.browser.mcp

import ai.platon.pulsar.browser.Browser
import ai.platon.pulsar.browser.BrowserManager
import ai.platon.pulsar.browser.WebDriver
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal session manager backed by a [ConcurrentHashMap].
 *
 * Sessions are ephemeral — no authentication, no persistence, no expiry.
 * Each session owns one [Browser] instance launched via [BrowserManager].
 */
class MCPSessionManager(
    private val browserManager: BrowserManager
) {
    data class SessionEntry(
        val sessionId: String,
        val browser: Browser,
        val createdAt: Instant = Instant.now()
    ) {
        /** The currently focused driver, or null if no tabs exist. */
        fun frontDriver(): WebDriver? = browser.frontDriver

        /** Get the front driver or throw. */
        fun requireFrontDriver(): WebDriver =
            frontDriver() ?: throw IllegalStateException("No active tab in session $sessionId")
    }

    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    /**
     * Launch a new ephemeral browser, create an initial tab, and register the session.
     */
    fun openSession(): SessionEntry {
        val sessionId = UUID.randomUUID().toString()
        val browser = browserManager.launchRandomTempBrowser()
        browser.newDriver() // ensure a front driver exists immediately
        val entry = SessionEntry(sessionId, browser)
        sessions[sessionId] = entry
        return entry
    }

    fun getSession(sessionId: String): SessionEntry? = sessions[sessionId]

    fun requireSession(sessionId: String): SessionEntry =
        getSession(sessionId) ?: throw IllegalArgumentException("Session not found: $sessionId")

    fun closeSession(sessionId: String): Boolean {
        val entry = sessions.remove(sessionId) ?: return false
        browserManager.closeBrowser(entry.browser)
        return true
    }

    fun listSessions(): List<SessionEntry> = sessions.values.toList()

    fun closeAllSessions(): Int {
        val ids = sessions.keys().toList()
        ids.forEach { closeSession(it) }
        return ids.size
    }

    fun close() {
        closeAllSessions()
        browserManager.close()
    }
}
