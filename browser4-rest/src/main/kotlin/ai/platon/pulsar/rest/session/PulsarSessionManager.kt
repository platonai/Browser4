package ai.platon.pulsar.rest.session

import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.chrome.protocol.transport.ExtensionChromeService
import ai.platon.pulsar.chrome.protocol.transport.ExtensionMessageSender
import ai.platon.pulsar.common.B4Constants.BROWSER_PROFILE_MODE
import ai.platon.pulsar.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.common.B4Constants.PROFILE_MODE_CAPABILITY
import ai.platon.pulsar.common.B4Constants.SESSION_ID_CAPABILITY
import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.context.AbstractAgenticContext
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_CONTEXT_MODE
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.core.api.PulsarSettings
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages AgenticSession-backed browser sessions and their lifecycle.
 *
 * This component is framework-agnostic and can be wired manually or exposed
 * through an external dependency injection container.
 *
 * # Session lifecycle
 *
 * - Sessions live **in memory only** — a backend restart loses every session
 *   (clients detect this via `list_sessions` and recreate on next use).
 * - Non-default sessions that have not been accessed for longer than
 *   [idleSessionTimeout] (default 4 hours) are reaped automatically by an
 *   internal idle reaper.  The default session, the shared swarm session and
 *   all attached (CDP / extension) sessions are **never** reaped.
 * - Unhealthy Browser4-launched sessions are recreated transparently on the
 *   next access (same session ID, fresh browser); attached sessions are never
 *   recreated implicitly.
 */
class PulsarSessionManager(
    val agenticContext: AgenticContext,
    /** Idle timeout after which non-default sessions are reaped. */
    private val idleSessionTimeout: Duration = DEFAULT_IDLE_SESSION_TIMEOUT,
    /**
     * Optional file for persisting the display-name → session-id mapping
     * across backend restarts.  When null, the mapping is in-memory only
     * (default for tests); when set, every mapping change is written through.
     */
    private val registryFile: Path? = null,
) : Closeable {
    private val logger = LoggerFactory.getLogger(PulsarSessionManager::class.java)

    /**
     * Reaps non-default sessions that have been idle for longer than
     * [idleSessionTimeout].
     *
     * The default session (the CLI's unnamed slot) is never reaped so
     * `open`-without-`-s` keeps reusing it across invocations.  The shared
     * swarm session is kept by design (it is shared across requests), and
     * attached (CDP / extension) sessions are kept because their browser is
     * external — tearing it down implicitly would sever the user's real
     * browser connection.
     *
     * @return the number of sessions reaped.
     */
    fun reapIdleSessions(): Int {
        val now = System.currentTimeMillis()
        val defaultSessionId = displayNameToSessionId[DEFAULT_SESSION_ID]
        val idleCandidates = sessions.entries.filter { (sessionId, session) ->
            !sessionId.equals(SWARM_SESSION_ID, ignoreCase = true) &&
                sessionId != defaultSessionId &&
                session.kind == SessionKind.BROWSER4_LAUNCHED &&
                now - session.lastAccessedAt > idleSessionTimeout.toMillis()
        }
        idleCandidates.forEach { (sessionId, session) ->
            logger.info(
                "Reaping idle session {} (idle for {} min, timeout {} min)",
                sessionId,
                (now - session.lastAccessedAt) / 60_000,
                idleSessionTimeout.toMinutes()
            )
            deleteSession(sessionId)
        }
        if (idleCandidates.isNotEmpty()) {
            logger.info("Reaped {} idle session(s)", idleCandidates.size)
        }
        return idleCandidates.size
    }

    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    /** Maps display names (e.g. "DEFAULT") to UUID-based session IDs for consistent reuse. */
    private val displayNameToSessionId = ConcurrentHashMap<String, String>()

    /**
     * Periodic idle-session reaper.  Runs on a daemon thread so it never
     * blocks JVM shutdown; stopped in [shutdown].
     */
    private val idleReaperExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "pulsar-session-idle-reaper").apply { isDaemon = true }
        }

    init {
        // Restore the display-name mapping across restarts (best effort).
        loadSessionRegistry()
        // Scan for idle sessions periodically.  The initial delay avoids doing
        // pointless work right after startup (nothing can be idle yet).
        idleReaperExecutor.scheduleWithFixedDelay(
            {
                runCatching { reapIdleSessions() }.onFailure { e ->
                    logger.warn("Idle session reaper failed: {}", e.message, e)
                }
            },
            IDLE_REAP_INITIAL_DELAY_MINUTES,
            IDLE_REAP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
    }

    // ------------------------------------------------------------------
    // Display-name registry persistence
    // ------------------------------------------------------------------

    /**
     * Loads the display-name → session-id mapping from [registryFile], if
     * configured.  Restored entries whose sessions no longer exist are simply
     * reused (same UUID) the next time the display name is opened — the UUID
     * identity is preserved even though the browser state is gone.
     */
    private fun loadSessionRegistry() {
        val file = registryFile ?: return
        runCatching {
            if (Files.exists(file)) {
                val raw = Files.readString(file)
                val parsed = pulsarObjectMapper().readValue(raw, Map::class.java)
                parsed.forEach { (key, value) ->
                    val name = key?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                    val sessionId = value?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                    displayNameToSessionId.putIfAbsent(name, sessionId)
                }
                logger.info("Loaded {} display-name mappings from session registry {}", displayNameToSessionId.size, file)
            }
        }.onFailure { e ->
            logger.warn("Failed to load session registry from {}: {}", file, e.message)
        }
    }

    /**
     * Writes the display-name → session-id mapping to [registryFile] (atomic
     * temp-file + rename).  Best effort — a failed write is logged, never
     * thrown, so session operations are not blocked by disk issues.
     */
    private fun persistSessionRegistry() {
        val file = registryFile ?: return
        runCatching {
            file.parent?.let(Files::createDirectories)
            val json = pulsarObjectMapper().writeValueAsString(displayNameToSessionId)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { e ->
            logger.warn("Failed to persist session registry to {}: {}", file, e.message)
        }
    }

    /**
     * The swarm session is a special session that used for swarm use cases. It is created on demand and shared
     * across all requests that specify the swarm session ID. The profile mode is pinned to SEQUENTIAL or TEMPORARY
     * based on the input capabilities, but defaults to SEQUENTIAL if not specified or invalid. The swarm session
     * is designed to be shared across multiple requests, so it should not be recreated if it already exists.
     * The capabilities can be used to create the swarm session profile if the swarm session does not exist,
     * or will be ignored if the swarm session is already exists.
     *
     * The swarm session may launch multiple browser contexts, each browser context isolated and has its own profile,
     * but all browser contexts share the same session-level profile which is determined by the profile mode.
     * */
    fun ensureSwarmSession(capabilities: Map<String, String?>? = null): ManagedSession {
        val sessionId = SWARM_SESSION_ID
        val normalizedCapabilities = normalizeCapabilities(sessionId, capabilities).toMutableMap()
        normalizedCapabilities[SESSION_ID_CAPABILITY] = sessionId
        // normalizedCapabilities[BROWSER_PROFILE_MODE] = browserProfileMode

        val settings = PulsarSettings.parse(normalizedCapabilities)
        val agenticSession = AgenticContexts.ensureSwarmSession(settings, (agenticContext as AbstractAgenticContext).applicationContext)

        // val pulsarSession = session.agenticSession

        // post check if the session is a swarm session
//        require(pulsarSession.boundBrowser == null)
//        require(pulsarSession.boundDriver == null)
        val conf = agenticSession.sessionConfig

        val browserProfileMode = conf.getWithFallback(BROWSER_PROFILE_MODE, BROWSER_CONTEXT_MODE)
        require(browserProfileMode == BrowserProfileMode.SEQUENTIAL.name || browserProfileMode == BrowserProfileMode.TEMPORARY.name) {
            "Swarm session must have profile mode SEQUENTIAL or TEMPORARY, but got $browserProfileMode"
        }

        val session = sessions.computeIfAbsent(sessionId) {
            ManagedSession(sessionId, agenticSession, normalizedCapabilities, kind = SessionKind.SWARM)
        }

        return session
    }

    fun getOrCreateSession(sessionId: String, capabilities: Map<String, String?>? = null): ManagedSession {
        // Route SWARM sessions before computeIfAbsent — ensureSwarmSession
        // internally calls computeIfAbsent, and ConcurrentHashMap forbids
        // nested computeIfAbsent on the same key.
        if (sessionId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
            return ensureSwarmSession(capabilities)
        }

        // Resolve DEFAULT to a stable UUID so all session types use
        // UUID-based IDs consistently (extension sessions already do).
        val resolvedId = if (sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true)) {
            generateDefaultSessionId()
        } else {
            sessionId
        }

        val normalizedCapabilities = normalizeCapabilities(resolvedId, capabilities)
        val session = sessions.computeIfAbsent(resolvedId) {
            createManagedSession(resolvedId, normalizedCapabilities)
        }

        val activeSession = resolveHealthySession(resolvedId, normalizedCapabilities, session)

        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    /**
     * Creates a new browser session with the specified capabilities.
     *
     * @param capabilities Optional browser capabilities (browserName, etc.)
     * @return The created managed session.
     */
    fun getOrCreateSession(capabilities: Map<String, String?>? = null): ManagedSession {
        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val requestedId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        // Route SWARM sessions before computeIfAbsent (see explanation above).
        if (requestedId.equals(SWARM_SESSION_ID, ignoreCase = true)) {
            return ensureSwarmSession(capabilities)
        }

        val session = sessions.computeIfAbsent(requestedId) {
            createManagedSession(requestedId, normalizedCapabilities)
        }
        val activeSession = resolveHealthySession(requestedId, normalizedCapabilities, session)
        activeSession.lastAccessedAt = System.currentTimeMillis()
        return activeSession
    }

    fun checkHealthyBlocking(session: ManagedSession): CheckState {
        return runBlocking { checkHealthy(session) }
    }

    suspend fun checkHealthy(session: ManagedSession): CheckState {
        val s = session.agenticSession
        val browser = s.boundBrowser
        val driver = s.boundDriver

        if (driver != null && driver.browser != browser) {
            logger.warn(
                "Inconsistent driver/browser. Driver {} state: {} browser {} state: {}",
                driver.id, driver.readableState, browser?.id, browser?.readableState
            )
        }

        var healthy = CheckState(if (s.isActive) 0 else -1)
        if (!healthy.isOK) {
            logger.warn("AgenticSession {} is not healthy", s.id)
        }

        if (healthy.isOK) {
            healthy = browser?.healthy() ?: CheckState()
            if (!healthy.isOK && browser != null) {
                logger.warn("Bound browser {} is unhealthy, state: {}", browser.id, browser.readableState)
            }

            if (healthy.isOK) {
                // For extension-attached sessions, the driver's CDP target may
                // not be "alive" until the first CDP command initializes the
                // protocol (Page.enable, etc.).  As long as the driver exists
                // and has left INIT state, the session is usable.
                val isExtensionSession = extensionBrowsers.containsKey(session.sessionId)
                if (isExtensionSession && driver != null && driver.readableState != "INIT") {
                    // Driver is ready — skip the strict healthy() check.
                } else {
                    healthy = driver?.healthy() ?: CheckState()
                }
                if (!healthy.isOK && driver != null) {
                    logger.warn("Bound driver {} is unhealthy, state: {}", driver.id, driver.readableState)
                }
            }
        }

        if (!healthy.isOK) {
            logger.warn(
                "Session {} is unhealthy: session active={}, browser healthy={}, driver healthy={}",
                session.sessionId,
                s.isActive,
                s.boundBrowser?.healthy() ?: "N/A",
                s.boundDriver?.healthy() ?: "N/A"
            )
        }

        return healthy
    }

    private fun resolveHealthySession(
        sessionId: String,
        capabilities: Map<String, String?>,
        session: ManagedSession,
    ): ManagedSession {
        // Extension-attached or CDP-attached sessions that still have a
        // connected WebSocket or active browser are healthy — return as-is
        // regardless of the kind field.
        if (extensionBrowsers.containsKey(sessionId)) {
            return markSessionActive(session)
        }

        // Extension-attached session whose WebSocket has disconnected.
        // Do NOT fall through to the non-owned/health paths — an extension
        // session without a connected WebSocket must stay inactive (and must
        // never be recreated as a Browser4-CDP session, which would silently
        // replace the extension-backed browser with a fresh Chrome the CLI
        // still displays as "Extension").
        if (extensionSessionIds.contains(sessionId)) {
            markSessionInactive(session)
            logger.info(
                "Extension-attached session {} is disconnected — keeping as inactive " +
                "(will not recreate as Browser4-CDP). Re-run attach --extension to reconnect.",
                sessionId
            )
            return session
        }

        // Sessions that do NOT own their browser must never be recreated by
        // Browser4 — that would launch a new browser instance, severing the
        // link to the user's existing browser.
        if (!session.kind.ownsBrowser) {
            // All non-owned sessions without a connected browser: check
            // health, but never recreate.  The user must re-attach manually.
            if (checkHealthyBlocking(session).isOK) {
                return markSessionActive(session)
            }

            markSessionInactive(session)
            logger.info(
                "Non-owned session {} is unhealthy — keeping as inactive " +
                "(will not recreate). Re-attach to reconnect.",
                sessionId
            )
            return session
        }

        if (checkHealthyBlocking(session).isOK) {
            return markSessionActive(session)
        }

        // The browser process may be perfectly healthy while only the driver
        // link (the CDP connection to the backend tab) is dead — e.g. the
        // machine slept and the websocket died, or the backend tab was closed.
        // Rebind a fresh driver to the SAME browser instance first: the Chrome
        // profile (cookies, manual logins) is preserved, so the session comes
        // back where it was instead of as a fresh anonymous browser.
        if (recoverLostDriverLink(session)) {
            logger.info("Recovered session {} by rebinding a new driver to the same browser", sessionId)
            return markSessionActive(session)
        }

        // The browser process may be perfectly healthy while only the driver
        // link (the CDP connection to the backend tab) is dead — e.g. the
        // machine slept and the websocket died, or the backend tab was closed.
        // Rebind a fresh driver to the SAME browser instance first: the Chrome
        // profile (cookies, manual logins) is preserved, so the session comes
        // back where it was instead of as a fresh anonymous browser.
        if (recoverLostDriverLink(session)) {
            logger.info("Recovered session {} by rebinding a new driver to the same browser", sessionId)
            return markSessionActive(session)
        }

        val recreatedSession = recreateUnhealthySession(sessionId, capabilities, session)
        return if (checkHealthyBlocking(recreatedSession).isOK) {
            markSessionActive(recreatedSession)
        } else {
            markSessionInactive(recreatedSession)
            logger.warn("Replacement session {} is still unhealthy after recreation", sessionId)
            recreatedSession
        }
    }

    private fun createManagedSession(
        sessionId: String,
        capabilities: Map<String, String?>,
        kind: SessionKind = SessionKind.BROWSER4_LAUNCHED,
    ): ManagedSession {
        val settings = PulsarSettings.parse(capabilities)
        val agenticSession = agenticContext.createSession(settings)

        return ManagedSession(
            sessionId = sessionId,
            agenticSession = agenticSession,
            capabilities = capabilities,
            kind = kind,
            status = if (agenticSession.isActive) SessionStatus.ACTIVE else SessionStatus.STOPPED
        ).also {
            logger.info("Created session {} with capabilities: {}", sessionId, capabilities)
        }
    }

    /**
     * Creates a session and attaches it to an already-running browser via CDP.
     *
     * Unlike [getOrCreateSession], this does not launch a new browser process.
     * Instead, it connects to an existing browser at the given CDP endpoint or port
     * and binds it to the newly created session.
     *
     * @param cdpEndpoint A CDP HTTP endpoint URL (e.g. "http://localhost:9222").
     * @param cdpPort A CDP port number. Used when [cdpEndpoint] is null.
     * @param capabilities Optional session capabilities.
     * @return The created managed session with the external browser bound.
     */
    fun createAttachedSession(
        cdpEndpoint: String? = null,
        cdpPort: Int? = null,
        capabilities: Map<String, String?>? = null,
    ): ManagedSession {
        require(cdpEndpoint != null || cdpPort != null) {
            "attach_browser requires either 'cdpEndpoint' (URL) or 'cdpPort' (number)"
        }

        val normalizedCapabilities = normalizeCapabilities(capabilities = capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        val port = when {
            cdpPort != null -> cdpPort
            cdpEndpoint != null -> parsePortFromEndpoint(cdpEndpoint)
            else -> throw IllegalArgumentException("No CDP endpoint or port provided")
        }

        // Verify the CDP endpoint is actually reachable and hosts a page target
        // BEFORE binding it to a session. Previously this step was skipped: a
        // `PulsarBrowser(port)` wrapper was bound unconditionally, so attach
        // reported success even when the endpoint pointed at a dead or wrong
        // browser (the CLI then "navigated" a window that never moved). Fail
        // loud here instead of silently attaching to nothing.
        val normalizedEndpoint = cdpEndpoint?.let { normalizeCdpEndpoint(it, port) }
            ?: "http://127.0.0.1:$port"
        val verification = verifyCdpEndpoint(normalizedEndpoint)
        if (!verification.reachable) {
            throw IllegalArgumentException(
                "CDP endpoint $normalizedEndpoint is not reachable: ${verification.detail}. " +
                    "Start the target browser with --remote-debugging-port and retry attach."
            )
        }
        if (verification.pageTargetCount == 0) {
            throw IllegalArgumentException(
                "CDP endpoint $normalizedEndpoint is reachable but has no page targets " +
                    "(${verification.detail}). Open a tab in the target browser, then retry attach."
            )
        }
        logger.info(
            "CDP attach verification OK | {} | browser={} | pageTargets={}",
            normalizedEndpoint, verification.browser, verification.pageTargetCount
        )

        // Mark the session CDP_ATTACHED (non-owned) so resolveHealthySession
        // never silently recreates it with a fresh Browser4-launched Chrome —
        // that would sever the link to the user's browser and lose its profile.
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities, SessionKind.CDP_ATTACHED)
        }

        // Bind the external browser to the session
        val browser = PulsarBrowser(port = port, settings = BrowserSettings())
        session.agenticSession.bindBrowser(browser)

        logger.info(
            "Attached session {} to browser at port {} (endpoint: {})",
            sessionId, port, cdpEndpoint ?: "N/A"
        )

        return session
    }

    /**
     * Result of a CDP endpoint verification probe.
     */
    data class CdpEndpointVerification(
        val reachable: Boolean,
        val browser: String?,
        val pageTargetCount: Int,
        val detail: String,
    )

    /**
     * Normalize a CDP endpoint into an HTTP base URL for verification.
     * Accepts `http://host:port`, `ws://host:port/path`, and bare `host:port`.
     */
    companion object {
        /** Default idle timeout before a non-default session is reaped. */
        val DEFAULT_IDLE_SESSION_TIMEOUT: Duration = Duration.ofHours(4)

        /** Delay before the first idle-session scan after manager startup (minutes). */
        private const val IDLE_REAP_INITIAL_DELAY_MINUTES = 30L

        /** Interval between idle-session scans (minutes). */
        private const val IDLE_REAP_INTERVAL_MINUTES = 30L

        fun normalizeCdpEndpoint(endpoint: String, port: Int): String {
            val trimmed = endpoint.trim()
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    trimmed.trimEnd('/')
                trimmed.startsWith("ws://") -> {
                    val withoutScheme = trimmed.removePrefix("ws://")
                    val host = withoutScheme.substringBefore('/')
                    "http://$host"
                }
                else -> "http://$trimmed"
            }.let { base ->
                // Ensure a port is present; ws/http endpoints may omit it.
                val uri = URI(base)
                if (uri.port > 0) base else "http://${uri.host ?: "127.0.0.1"}:$port"
            }
        }

        /**
         * Probe a CDP HTTP endpoint: `GET /json/version` proves the browser is
         * reachable and identifies it; `GET /json` counts page targets so we never
         * attach to a browser that has nothing to navigate.
         */
        fun verifyCdpEndpoint(baseUrl: String): CdpEndpointVerification {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()

            val version = try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/json/version"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build()
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                return CdpEndpointVerification(
                    reachable = false,
                    browser = null,
                    pageTargetCount = 0,
                    detail = e.message ?: e.javaClass.simpleName
                )
            }
            if (version.statusCode() !in 200..299) {
                return CdpEndpointVerification(
                    reachable = false,
                    browser = null,
                    pageTargetCount = 0,
                    detail = "GET /json/version → HTTP ${version.statusCode()}"
                )
            }

            val browser = runCatching {
                val body = com.fasterxml.jackson.databind.ObjectMapper().readTree(version.body())
                body.path("Browser").asText("").takeIf { it.isNotBlank() }
                    ?: body.path("browser").asText("")
            }.getOrNull()

            val pageTargets = try {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/json"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    return CdpEndpointVerification(
                        reachable = true,
                        browser = browser,
                        pageTargetCount = 0,
                        detail = "GET /json → HTTP ${response.statusCode()}"
                    )
                }
                val array = com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body())
                // Count only page targets; ignore browser_ui / service_worker etc.
                var pages = 0
                for (node in array) {
                    if (node.path("type").asText("") == "page") pages++
                }
                pages
            } catch (e: Exception) {
                return CdpEndpointVerification(
                    reachable = true,
                    browser = browser,
                    pageTargetCount = 0,
                    detail = "GET /json failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }

            return CdpEndpointVerification(
                reachable = true,
                browser = browser,
                pageTargetCount = pageTargets,
                detail = "browser=$browser pages=$pageTargets"
            )
        }
    }

    // ------------------------------------------------------------------
    // Extension-attached sessions (Browser4 Chrome Extension relay)
    // ------------------------------------------------------------------

    /** Sessions waiting for the extension to connect via WebSocket. */
    private val pendingExtensionConnections = ConcurrentHashMap<String, PendingExtensionConnection>()

    /** Active extension browser instances keyed by sessionId. */
    private val extensionBrowsers = ConcurrentHashMap<String, ExtensionChromeService>()

    /**
     * Tracks every session that was created via [createExtensionAttachedSession].
     *
     * Unlike [extensionBrowsers] (which only contains sessions whose WebSocket is
     * currently connected), this set is permanent for the lifetime of the session.
     * It allows [resolveHealthySession] to distinguish "extension-attached but
     * disconnected" from "ordinary Browser4-launched" sessions — the former must
     * never be silently recreated as a Browser4-CDP session.
     */
    private val extensionSessionIds = ConcurrentHashMap.newKeySet<String>()

    /** Injected by [ai.platon.pulsar.rest.config.ExtensionWebSocketConfig]. */
    @Volatile
    var serverPort: Int = 8182

    /**
     * Creates a pending session that will be bound to the Browser4 Chrome
     * Extension once it connects via WebSocket.
     *
     * @param channel Optional browser channel hint (chrome, msedge, etc.) — informational only.
     * @param capabilities Optional session capabilities.
     * @return Info containing the sessionId and the ws:// endpoint URL the extension should connect to.
     */
    fun createExtensionAttachedSession(
        channel: String? = null,
        capabilities: Map<String, String?>? = null,
    ): ExtensionSessionInfo {
        // Clean up stale pending connections (older than 2 minutes)
        val now = System.currentTimeMillis()
        val staleIterator = pendingExtensionConnections.entries.iterator()
        while (staleIterator.hasNext()) {
            val (sid, pending) = staleIterator.next()
            if (now - pending.createdAt > 120_000) {
                logger.info("Cleaning up stale pending extension connection | sessionId={}", sid)
                staleIterator.remove()
            }
        }

        // Generate a unique session ID for each extension connection.
        // Passing an explicit ID prevents the fallback to DEFAULT_SESSION_ID.
        val explicitSessionId = java.util.UUID.randomUUID().toString()
        val normalizedCapabilities = normalizeCapabilities(explicitSessionId, capabilities)
        val sessionId = normalizedCapabilities.getValue(SESSION_ID_CAPABILITY).toString()

        // Create the managed session (without a bound browser yet).
        val session = sessions.computeIfAbsent(sessionId) {
            createManagedSession(sessionId, normalizedCapabilities, SessionKind.EXTENSION_ATTACHED)
        }

        // An extension session is not usable until the extension connects via
        // WebSocket — mark it stopped up front (onExtensionConnected flips it
        // to ACTIVE).  Previously this state was derived lazily by the health
        // check inside getSession; with pure-lookup getSession it must be set
        // here explicitly.
        session.status = SessionStatus.STOPPED

        // Track this session as extension-attached so resolveHealthySession
        // never silently recreates it as an ordinary Browser4-CDP session.
        extensionSessionIds.add(sessionId)

        val wsEndpoint = "ws://127.0.0.1:$serverPort/ws/extension/$sessionId"
        pendingExtensionConnections[sessionId] = PendingExtensionConnection(
            sessionId = sessionId,
            wsEndpoint = wsEndpoint,
            createdAt = System.currentTimeMillis()
        )

        logger.info(
            "Created pending extension session {} at {} (channel={})",
            sessionId, wsEndpoint, channel ?: "default"
        )

        return ExtensionSessionInfo(sessionId, wsEndpoint)
    }

    /**
     * Called by [ExtensionWebSocketHandler] when an extension WebSocket
     * connection is established.  Creates an [ExtensionChromeService] wrapping
     * the connection and binds it as the browser for the pending session.
     */
    fun onExtensionConnected(sessionId: String, sender: ExtensionMessageSender) {
        val pending = pendingExtensionConnections.remove(sessionId)
            ?: throw IllegalStateException("No pending extension connection for session $sessionId")

        val managedSession = sessions[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        // Create the ExtensionChromeService that bridges the WebSocket
        // relay protocol to the internal ChromeService abstraction.
        val extChrome = ExtensionChromeService(sender, sessionId)

        // Wrap it as a PulsarBrowser so the session can use it.
        val browser = PulsarBrowser(
            id = ai.platon.pulsar.api.BrowserId.RANDOM_TEMP,
            chrome = extChrome,
            settings = BrowserSettings(),
            launcher = null
        )

        // Bind the browser to the agentic session.
        managedSession.agenticSession.bindBrowser(browser)
        managedSession.status = SessionStatus.ACTIVE
        extensionBrowsers[sessionId] = extChrome

        // Create and bind a driver from the extension browser in a background
        // thread.  We must NOT block the WebSocket handler thread — the
        // extension.initialized event is delivered on the same Jetty thread that
        // calls afterConnectionEstablished, so blocking here would deadlock event
        // delivery.
        val agenticSession = managedSession.agenticSession
        Thread {
            try {
                val deadline = System.currentTimeMillis() + 15_000
                var tabs = browser.listTabs()
                while (tabs.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                    tabs = browser.listTabs()
                }
                if (tabs.isNotEmpty()) {
                    val driver = browser.newDriverForTab(tabs.first())
                    driver.free()
                    // Initialize CDP so the driver is operational.
                    runBlocking { driver.browserProtocol.pageEnable() }
                    agenticSession.bindDriver(driver)
                    logger.info(
                        "Created extension driver for session {} (tab: {})",
                        sessionId, driver.chromeTab.url
                    )
                } else {
                    logger.warn(
                        "No tabs available for extension session {} after waiting",
                        sessionId
                    )
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to create extension driver for session {}: {}",
                    sessionId, e.message, e
                )
            }
        }.apply {
            name = "extension-driver-binder-$sessionId"
            isDaemon = true
            start()
        }

        logger.info(
            "Extension connected and bound to session {} | elapsed={}ms",
            sessionId, System.currentTimeMillis() - pending.createdAt
        )
    }

    /**
     * Routes an incoming text message from the extension WebSocket to the
     * appropriate [ExtensionChromeService].
     */
    fun routeExtensionMessage(sessionId: String, message: String) {
        extensionBrowsers[sessionId]?.handleIncomingMessage(message)
    }

    /**
     * Called when the extension WebSocket disconnects.  Closes the
     * [ExtensionChromeService] and marks the session stopped.
     */
    fun onExtensionDisconnected(sessionId: String) {
        val browser = extensionBrowsers.remove(sessionId)
        browser?.close()
        pendingExtensionConnections.remove(sessionId)

        logger.info("Extension disconnected from session {}", sessionId)

        // Mark the session as stopped so health checks reflect the state.
        sessions[sessionId]?.let { session ->
            if (session.status != SessionStatus.STOPPED) {
                session.status = SessionStatus.STOPPED
            }
        }
    }

    /**
     * Returns true if the extension has connected and the session has a bound
     * extension browser.
     */
    fun isExtensionSessionReady(sessionId: String): Boolean {
        return extensionBrowsers.containsKey(sessionId)
    }

    // ------------------------------------------------------------------
    // Internal data classes
    // ------------------------------------------------------------------

    data class ExtensionSessionInfo(
        val sessionId: String,
        val wsEndpoint: String
    )

    private data class PendingExtensionConnection(
        val sessionId: String,
        val wsEndpoint: String,
        val createdAt: Long
    )

    /**
     * Extracts the port number from a CDP endpoint URL.
     * Handles formats: http://host:port, ws://host:port/path, host:port
     * IPv6 addresses are supported when bracketed (e.g. [::1]:9222).
     */
    private fun parsePortFromEndpoint(endpoint: String): Int {
        // Try parsing as-is first, then with an http:// scheme prepended.
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            URI.create("http://$endpoint")
        }

        if (uri.port > 0) return uri.port

        // Fallback: extract host:port from the authority-like portion.
        // Strip scheme and path, then parse the remaining host:port.
        val hostPort = endpoint
            .substringAfter("://")       // drop scheme (http://, ws://, etc.)
            .substringBefore('/')        // drop path / query / fragment
            .substringBefore('?')
            .substringBefore('#')

        // IPv6: the port follows the closing bracket, e.g. [::1]:9222
        if (hostPort.contains(']')) {
            val afterBracket = hostPort.substringAfterLast(']')
            if (afterBracket.startsWith(':')) {
                return afterBracket.removePrefix(":").toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Could not parse CDP port from endpoint: $endpoint")
            }
            throw IllegalArgumentException(
                "IPv6 address must be followed by :port in endpoint: $endpoint")
        }

        // Plain host:port (IPv4 or hostname)
        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon >= 0) {
            return hostPort.substring(lastColon + 1).toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Could not parse CDP port from endpoint: $endpoint")
        }

        throw IllegalArgumentException(
            "Could not parse CDP port from endpoint: $endpoint")
    }

    private fun recreateUnhealthySession(
        sessionId: String,
        capabilities: Map<String, String?>,
        staleSession: ManagedSession,
    ): ManagedSession {
        return sessions.compute(sessionId) { _, existingSession ->
            when {
                existingSession == null -> createManagedSession(sessionId, capabilities)
                checkHealthyBlocking(existingSession).isOK -> {
                    if (existingSession.status != SessionStatus.ACTIVE) {
                        existingSession.status = SessionStatus.ACTIVE
                    }
                    existingSession
                }

                else -> {
                    markSessionInactive(existingSession)
                    if (existingSession === staleSession) {
                        logger.warn("Cached session {} is unhealthy, creating a replacement", sessionId)
                    } else {
                        logger.warn("Concurrent cached session {} is unhealthy, creating a replacement", sessionId)
                    }
                    createManagedSession(sessionId, capabilities)
                }
            }
        }!!
    }

    /**
     * Tries to recover a session whose browser process is healthy but whose
     * driver link (the CDP connection to the browser's backend tab) is lost —
     * e.g. after the machine slept and the websocket died, or the backend tab
     * was closed.
     *
     * Recovery prefers an in-place [WebDriver.reconnect] of the SAME driver
     * (same tab — pulsar 4.11.5+ re-establishes the CDP link and re-enables
     * the protocol agents). When that is not supported or fails, a fresh
     * driver is created on the SAME [Browser] instance and bound to the
     * session, so the Chrome profile (cookies, manual logins) is preserved
     * either way. The stale driver is unbound but its tab is left open — it
     * may be the user's visible page. Returns true when recovery succeeds;
     * the caller then proceeds with the normal recreate-with-fresh-profile
     * path otherwise.
     */
    private fun recoverLostDriverLink(session: ManagedSession): Boolean {
        val agenticSession = session.agenticSession
        val browser = agenticSession.boundBrowser ?: return false
        val staleDriver = agenticSession.boundDriver ?: return false

        // Browser itself is dead → the normal recreate path applies.
        if (!browser.healthy().isOK) return false
        // Driver link is fine → nothing to recover.
        if (runBlocking { staleDriver.healthy() }.isOK) return false

        return runCatching {
            // Preferred: reconnect the SAME driver (and the same tab) in place.
            if (runBlocking { staleDriver.reconnect() }) {
                return@runCatching true
            }

            // Fallback: bind a fresh driver on the SAME browser instance.
            // Plain field read on the stale driver — no CDP call, so this
            // cannot hang on the dead link. The new tab opens at the last
            // known URL to mirror what the user was looking at.
            val lastUrl = (staleDriver as? PulsarWebDriver)?.navigateUrl
            val rawDriver = browser.newDriver(lastUrl ?: "about:blank")
            val replacement = when (rawDriver) {
                is Browser4WebDriver -> rawDriver
                is PulsarWebDriver -> Browser4WebDriver.from(rawDriver)
                else -> rawDriver
            }

            // Unbind the stale driver before binding the replacement so
            // boundDriver (the first WebDriver bean) resolves to the new one.
            agenticSession.unbindDriver(staleDriver)
            agenticSession.bindDriver(replacement)

            runBlocking { replacement.healthy().isOK }
        }.getOrElse { e ->
            logger.warn("Failed to recover driver link for session {}: {}", session.sessionId, e.message)
            false
        }
    }

    private fun markSessionActive(session: ManagedSession): ManagedSession {
        if (session.status != SessionStatus.ACTIVE) {
            session.status = SessionStatus.ACTIVE
        }
        return session
    }

    private fun markSessionInactive(session: ManagedSession) {
        session.status = SessionStatus.STOPPED
    }

    private fun normalizeCapabilities(
        explicitSessionId: String? = null,
        capabilities: Map<String, String?>?,
    ): Map<String, String?> {
        val normalizedCapabilities = LinkedHashMap(capabilities.orEmpty())
        val hasExplicitSessionId = !explicitSessionId.isNullOrBlank()
        val requestedSessionId = normalizedCapabilities[SESSION_ID_CAPABILITY]?.trim()
        val sessionId = when {
            explicitSessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> generateDefaultSessionId()
            explicitSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
            hasExplicitSessionId -> displayNameToSessionId.getOrDefault(
                explicitSessionId.trim(),
                explicitSessionId.trim()
            )
            requestedSessionId.isNullOrBlank() || requestedSessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) -> generateDefaultSessionId()

            requestedSessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> SWARM_SESSION_ID
            else -> resolveOrCreateDisplayNameMapping(requestedSessionId)
        }

        val requestedProfileMode = BrowserProfileMode.fromString(
            normalizedCapabilities[PROFILE_MODE_CAPABILITY]?.toString()
        )

        normalizedCapabilities[SESSION_ID_CAPABILITY] = sessionId
        normalizedCapabilities[PROFILE_MODE_CAPABILITY] = when {
            sessionId.equals(SWARM_SESSION_ID, ignoreCase = true) -> when (requestedProfileMode) {
                BrowserProfileMode.TEMPORARY -> BrowserProfileMode.TEMPORARY
                BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
                else -> BrowserProfileMode.SEQUENTIAL
            }

            hasExplicitSessionId && sessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) -> BrowserProfileMode.DEFAULT

            sessionId.equals(
                DEFAULT_SESSION_ID,
                ignoreCase = true
            ) && requestedProfileMode == BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL

            sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true) -> BrowserProfileMode.DEFAULT
            requestedProfileMode == BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
            else -> BrowserProfileMode.SEQUENTIAL
        }.name

        return normalizedCapabilities
    }

    /**
     * Generates a stable UUID for the default session slot.  The first call
     * creates a new UUID and records the mapping; subsequent calls return the
     * same UUID so that repeated {@code open_session} calls reuse the same
     * session instead of creating a new one each time.
     */
    private fun generateDefaultSessionId(): String {
        return resolveOrCreateDisplayNameMapping(DEFAULT_SESSION_ID)
    }

    /**
     * Resolves a display name to its stable UUID, creating (and persisting)
     * the mapping on first use.
     *
     * Note: this must NOT persist from inside `ConcurrentHashMap.computeIfAbsent`
     * — the mapping function runs *before* the entry is inserted, so a
     * snapshot taken there would miss the new mapping.
     */
    private fun resolveOrCreateDisplayNameMapping(name: String): String {
        displayNameToSessionId[name]?.let { return it }
        val newId = UUID.randomUUID().toString()
        val raced = displayNameToSessionId.putIfAbsent(name, newId)
        if (raced == null) {
            persistSessionRegistry()
            return newId
        }
        return raced
    }

    /**
     * Retrieves a session by ID — **pure lookup**, no side effects.
     *
     * Unlike [getOrRecoverSession], this never creates the default session on
     * demand and never runs health checks / recreation.  Use it for read-only
     * paths (listing, readiness checks) where a stale or missing session must
     * surface as-is instead of being silently repaired.
     *
     * @param sessionId The session identifier (display name or UUID).
     * @return The managed session, or null if not found.
     */
    fun getSession(sessionId: String): ManagedSession? {
        val resolvedId = displayNameToSessionId.getOrDefault(sessionId, sessionId)
        val session = sessions[resolvedId]
        if (session == null) {
            logger.debug("getSession: {} not found in sessions (resolvedId={})", sessionId, resolvedId)
        }
        session?.lastAccessedAt = System.currentTimeMillis()
        return session
    }

    /**
     * Retrieves a session for use, recovering it when needed.
     *
     * Full previous [getSession] semantics: resolves display names, creates
     * the default session on demand, runs a health check and transparently
     * recreates unhealthy Browser4-launched sessions (same session ID, fresh
     * browser).  Use this on execution paths where a command must succeed
     * against a live browser.
     *
     * @param sessionId The session identifier (display name or UUID).
     * @return The managed session (possibly recreated), or null if not found.
     */
    fun getOrRecoverSession(sessionId: String): ManagedSession? {
        val resolvedId = displayNameToSessionId.getOrDefault(sessionId, sessionId)
        val session = if (resolvedId != sessionId) {
            // Look up by resolved UUID
            sessions[resolvedId]?.let { existingSession ->
                val normalizedCapabilities = normalizeCapabilities(
                    resolvedId,
                    existingSession.capabilities ?: mapOf(SESSION_ID_CAPABILITY to existingSession.sessionId)
                )
                resolveHealthySession(resolvedId, normalizedCapabilities, existingSession)
            }.also { if (it == null) logger.warn("getOrRecoverSession: resolvedId={} not found in sessions (displayName path, input={})", resolvedId, sessionId) }
        } else if (sessionId.equals(DEFAULT_SESSION_ID, ignoreCase = true)) {
            getOrCreateSession(mapOf(SESSION_ID_CAPABILITY to DEFAULT_SESSION_ID))
        } else {
            sessions[sessionId]?.let { existingSession ->
                val normalizedCapabilities = normalizeCapabilities(
                    sessionId,
                    existingSession.capabilities ?: mapOf(SESSION_ID_CAPABILITY to existingSession.sessionId)
                )
                resolveHealthySession(sessionId, normalizedCapabilities, existingSession)
            }.also { if (it == null) logger.warn("getOrRecoverSession: sessionId={} not found in sessions (direct path). known keys={}", sessionId, sessions.keys().toList().take(10)) }
        }
        session?.lastAccessedAt = System.currentTimeMillis()
        return session
    }

    /**
     * Deletes a session and cleans up resources.
     *
     * The session ID may be a display name (e.g. "team-a") or the resolved
     * UUID — both are accepted, mirroring [getSession].
     *
     * @param sessionId The session identifier (display name or UUID).
     * @return True if the session was deleted, false if not found.
     */
    fun deleteSession(sessionId: String): Boolean {
        // Resolve display names to their UUID, matching getSession.  Without
        // this, closing by display name silently "fails" and leaks the session.
        val resolvedId = displayNameToSessionId.getOrDefault(sessionId, sessionId)
        val session = sessions.remove(resolvedId) ?: return false

        try {
            val pulsarSession = session.agenticSession
            val browser = pulsarSession.boundBrowser

            // logger.info("---------------------DELETE MANAGED SESSION BEGIN----------------------------")
            logger.info(
                "---- Deleting session `{}`, closing pulsar session #{} {}",
                resolvedId, pulsarSession.id, pulsarSession.display
            )

            // Close the session AND deregister it from the context's session
            // registry. A plain close() leaves a zombie session in
            // context.sessions; `ensureSwarmSession` looks sessions up by label
            // and would otherwise return the closed swarm session forever,
            // leaving every new swarm task "queued" and never consumed.
            runCatching { pulsarSession.context.closeSession(pulsarSession) }
                .onFailure { e ->
                    logger.warn("Failed to deregister session {}, falling back to plain close: {}", sessionId, e.message)
                    pulsarSession.close()
                }
            // Close the companion browser if it exists
            if (browser != null) {
                // might be already closed by the session, but we ensure it's closed here to release resources
                // TODO: remove this redundant close call after confirming that session.close() always closes the browser
                pulsarSession.context.browserManager.closeBrowser(browser)
            }

            logger.info("---- Deleted session `{}` and released resources", resolvedId)
            // logger.info("----------------------DELETE MANAGED SESSION END---------------------------")
        } catch (e: Exception) {
            logger.error("Error closing session {}: {}", resolvedId, e.message, e)
        }

        // Clean up extension-related resources for this session
        extensionBrowsers.remove(resolvedId)?.close()
        pendingExtensionConnections.remove(resolvedId)
        extensionSessionIds.remove(resolvedId)

        // Clean up the display-name mapping if this session was a default session
        if (displayNameToSessionId.values.remove(resolvedId)) {
            persistSessionRegistry()
        }

        return true
    }

    /**
     * Returns all active sessions.
     *
     * @return A list of all managed sessions.
     */
    fun getAllSessions(): List<ManagedSession> {
        return sessions.values.toList()
    }

    /**
     * Deletes all active sessions and releases their resources.
     *
     * @return The number of sessions deleted.
     */
    fun deleteAllSessions(): Int {
        val count = sessions.size
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
        return count
    }

    /**
     * Closes all active sessions managed by this instance.
     */
    fun shutdown() {
        idleReaperExecutor.shutdownNow()
        logger.info("Shutting down SessionManager, closing {} active sessions", sessions.size)
        sessions.keys.toList().forEach { sessionId ->
            deleteSession(sessionId)
        }
    }

    override fun close() {
        shutdown()
    }
}
