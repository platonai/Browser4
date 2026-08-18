package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Open-page screenshots for the web pages panel at `/pages.html`.
 *
 * - `GET /api/pages` — one entry per open tab across all sessions. The active
 *   tab of a normal session is marked `active=true` and carries a screenshot
 *   URL (captured on demand); other tabs and every tab of a [SessionKind.SWARM]
 *   session are marked `placeholder=true` and must be shown with a placeholder
 *   image instead. Swarm sessions never provide screenshots.
 * - `GET /api/pages/{sessionId}/{guid}/screenshot.png` — **asynchronous**
 *   screenshot loading. A fresh cached capture is returned immediately as
 *   `image/png`; otherwise a capture is scheduled in the background and the
 *   response is `202 Accepted` (with `Retry-After`) so the panel can poll the
 *   same URL until the PNG is ready. `?refresh=1` forces a new capture.
 *
 * Captures run on a single background thread (WebDriver calls must not run
 * concurrently) and are keyed per tab: concurrent requests for the same tab
 * share one in-flight task, and a short TTL avoids re-capturing on every
 * panel auto-refresh.
 */
@RestController
@CrossOrigin
@RequestMapping("api/pages")
class PagesController(
    val sessionManager: PulsarSessionManager,
) {
    private val logger = LoggerFactory.getLogger(PagesController::class.java)

    /** A successfully captured PNG with its capture time. */
    private data class ScreenshotEntry(val png: ByteArray, val capturedAt: Long)

    private val screenshotCache = ConcurrentHashMap<String, ScreenshotEntry>()
    private val lastFailureAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Single-threaded so WebDriver calls are never made concurrently. */
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pages-screenshot-capture").apply { isDaemon = true }
    }
    private val captureScope = CoroutineScope(captureExecutor.asCoroutineDispatcher() + SupervisorJob())

    /**
     * List every open page (tab) across all sessions.
     *
     * Per-tab metadata queries are CDP round trips, so each tab is bounded by
     * [PAGE_QUERY_TIMEOUT_MS] and a busy session degrades to `title`/`url`
     * being empty instead of blocking the panel.
     */
    @GetMapping
    suspend fun pages(): Map<String, Any?> {
        val items = sessionManager.getAllSessions().flatMap { s -> sessionPages(s) }
        val liveCount = items.count { it["placeholder"] == false }
        val placeholderCount = items.count { it["placeholder"] == true }
        val sessions = items.map { it["sessionId"] as String }.distinct().size
        return mapOf(
            "total" to items.size,
            "live" to liveCount,
            "placeholder" to placeholderCount,
            "sessions" to sessions,
            "items" to items,
        )
    }

    /**
     * Asynchronously load the screenshot of one tab.
     *
     * - 200 `image/png` — fresh capture from the cache (or completed between
     *   polls). The cache is keyed per tab and ignores query-string cache
     *   busters, so the panel's auto-refresh reuses the same capture.
     * - 202 Accepted — a capture is being taken (or was just scheduled);
     *   poll the same URL after `Retry-After` seconds.
     * - 403 — swarm sessions never provide screenshots.
     * - 404 — unknown session or tab.
     * - 504 — the most recent capture attempt failed; retry later.
     */
    @GetMapping("{sessionId}/{guid}/screenshot.png")
    suspend fun screenshot(
        @PathVariable sessionId: String,
        @PathVariable guid: String,
        @RequestParam(defaultValue = "false") refresh: Boolean = false,
    ): ResponseEntity<Any> {
        val session = sessionManager.getSession(sessionId)
            ?: return jsonError(HttpStatus.NOT_FOUND, "session not found: $sessionId")
        if (session.kind == SessionKind.SWARM) {
            return jsonError(
                HttpStatus.FORBIDDEN,
                "swarm sessions only show placeholder pages, screenshots are not available"
            )
        }
        val webDriver = findDriver(session, guid)
            ?: return jsonError(HttpStatus.NOT_FOUND, "tab not found: $guid")

        val key = "$sessionId/$guid"

        if (refresh) {
            screenshotCache.remove(key)
        }
        screenshotCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.capturedAt <= SCREENSHOT_TTL_MS) {
                return pngResponse(cached.png)
            }
            screenshotCache.remove(key)
        }

        // A recent failure short-circuits the 202 loop so the panel can show
        // the failure state instead of polling forever.
        val failedAt = lastFailureAt[key] ?: 0L
        if (System.currentTimeMillis() - failedAt <= FAILURE_TTL_MS) {
            return jsonError(HttpStatus.GATEWAY_TIMEOUT, "screenshot failed or timed out for tab $guid")
        }

        scheduleCapture(session, webDriver, key)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Retry-After", "1")
            .body(mapOf("status" to "capturing"))
    }

    /**
     * Schedule a background capture for a tab. Concurrent requests for the
     * same tab share one in-flight task; the result lands in the cache so the
     * next poll returns the PNG.
     */
    private fun scheduleCapture(session: ManagedSession, webDriver: AbstractWebDriver, key: String) {
        if (!inFlight.add(key)) return
        captureScope.launch {
            try {
                val png = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                    session.mutex.withLock {
                        try {
                            webDriver.screenshot()?.let { Base64.getDecoder().decode(it) }
                        } catch (e: Exception) {
                            logger.warn("Screenshot failed for session {} tab {}: {}", session.sessionId, key, e.message)
                            null
                        }
                    }
                }
                if (png != null) {
                    screenshotCache[key] = ScreenshotEntry(png, System.currentTimeMillis())
                } else {
                    lastFailureAt[key] = System.currentTimeMillis()
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun pngResponse(png: ByteArray): ResponseEntity<Any> {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_PNG)
            .body(png)
    }

    private fun jsonError(status: HttpStatus, message: String): ResponseEntity<Any> {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("error" to message))
    }

    // ---- helpers ----

    private suspend fun sessionPages(s: ManagedSession): List<Map<String, Any?>> {
        val browser = s.agenticSession.boundBrowser ?: return emptyList()
        // Pure in-memory driver registry — no CDP round trips for enumeration.
        val drivers = try {
            browser.drivers.values.filterIsInstance<AbstractWebDriver>().toList()
        } catch (e: Exception) {
            logger.debug("Failed to list drivers for session {}: {}", s.sessionId, e.message)
            return emptyList()
        }
        // The front driver is only set after bringToFront(); fall back to the
        // first driver so a freshly opened single-tab session still reports
        // its active page.
        val frontGuid = try {
            (browser.frontDriver as? AbstractWebDriver)?.guid
        } catch (e: Exception) {
            null
        }
        val activeGuid = frontGuid ?: drivers.firstOrNull()?.guid
        return drivers.mapIndexed { index, wd ->
            val (title, url) = withTimeoutOrNull(PAGE_QUERY_TIMEOUT_MS) {
                try {
                    wd.title() to wd.currentUrl()
                } catch (e: Exception) {
                    "" to ""
                }
            } ?: ("" to "")
            val placeholder = s.kind == SessionKind.SWARM
            mapOf(
                "sessionId" to s.sessionId,
                "kind" to s.kind.name,
                "sessionStatus" to s.status,
                "tabIndex" to index,
                "guid" to wd.guid,
                "title" to title,
                "url" to url,
                "active" to (wd.guid == activeGuid),
                "placeholder" to placeholder,
                "screenshotUrl" to if (placeholder) {
                    null
                } else {
                    "/api/pages/${encodePath(s.sessionId)}/${encodePath(wd.guid)}/screenshot.png"
                },
            )
        }
    }

    private fun findDriver(s: ManagedSession, guid: String): AbstractWebDriver? {
        val browser = s.agenticSession.boundBrowser ?: return null
        return try {
            browser.drivers.values.filterIsInstance<AbstractWebDriver>().firstOrNull { it.guid == guid }
        } catch (e: Exception) {
            null
        }
    }

    private fun encodePath(segment: String): String {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PagesController::class.java)
        private const val PAGE_QUERY_TIMEOUT_MS = 3000L
        private const val SCREENSHOT_TIMEOUT_MS = 8000L
        /** Cache a successful capture for this long so panel auto-refresh reuses it. */
        private const val SCREENSHOT_TTL_MS = 10_000L
        /** After a failed capture, fail fast for this long before retrying. */
        private const val FAILURE_TTL_MS = 5_000L
    }
}
