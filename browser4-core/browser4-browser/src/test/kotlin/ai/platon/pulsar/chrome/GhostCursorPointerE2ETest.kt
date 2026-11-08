package ai.platon.pulsar.chrome

import ai.platon.browser4.behavior.BrowserBehavior
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.chrome.manage.PulsarBrowserFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * End-to-end proof that a pinned `ghostcursor` humanizer reaches the page through the driver.
 *
 * The unit tests pin the policy and the dispatch mapping; only a real browser can show that the
 * *page* sees the result: an ordered, trusted `mousemove` trail, a press that is actually held,
 * and a pointer that drifts once the session goes idle (the driver-owned coroutine).
 *
 * The page is a fixture served by the JDK HTTP server on an ephemeral port, so the test needs no
 * external site and no shared test module (which would depend on this one).
 *
 * The provider is pinned through system properties in the companion initializer, before the
 * browser — and therefore the configuration snapshot the driver reads — is created. Tagged
 * [ManualOnly] (plus `RequiresBrowser` / `E2E`), so neither `mvn test` nor CI launches a browser:
 *
 * ```bash
 * mvn test -pl browser4-core/browser4-browser \
 *     -Dtest=GhostCursorPointerE2ETest -D"surefire.excludedGroups="
 * ```
 */
@Tag("E2E")
@Tag("RequiresBrowser")
@Tag("ManualOnly")
@DisplayName("ghostcursor pointer trajectory (real browser)")
class GhostCursorPointerE2ETest {

    private lateinit var factory: PulsarBrowserFactory
    private lateinit var browser: Browser
    private lateinit var server: HttpServer
    private lateinit var fixtureUrl: String
    private var driver: WebDriver? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(FIXTURE_PATH) { exchange ->
            val body = FIXTURE_HTML.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        fixtureUrl = "http://127.0.0.1:${server.address.port}$FIXTURE_PATH"

        factory = PulsarBrowserFactory()
        browser = factory.launchRandomTempBrowser()
    }

    @AfterEach
    fun tearDown() {
        runCatching { driver?.close() }
        runCatching { browser.close() }
        runCatching { server.stop(0) }
    }

    @Test
    @DisplayName("a click travels along the planned trajectory as trusted input")
    fun clickTravelsAlongThePlannedTrajectory() = runBlocking {
        val b4Driver = openDriver()
        assertGhostCursorIsActive()

        b4Driver.click("#far")

        val moves = numberProbe(b4Driver, "window.__probe.moves.length")
        // ghostcursor plans `25 + 5 · fitts` events, while the community default caps the step
        // count at 30, so this is the cheapest assertion that separates the two policies.
        assertTrue(moves >= 35, "the page observed only $moves pointer moves; ghostcursor plans far more")
        assertEquals(
            true,
            b4Driver.evaluateValue(EVERY_MOVE_IS_TRUSTED),
            "every mousemove must be trusted CDP input",
        )
        assertEquals(
            INSIDE,
            b4Driver.evaluateValue(MOUSE_DOWN_INSIDE_BUTTON)?.toString()?.substringBefore(' '),
            "the click must land inside the button: ${b4Driver.evaluateValue(MOUSE_DOWN_INSIDE_BUTTON)}",
        )
        val holdMillis = numberProbe(b4Driver, "window.__probe.up - window.__probe.down")
        assertTrue(holdMillis >= 10, "the button was held for $holdMillis ms; a 0 ms press is a bot fingerprint")
    }

    @Test
    @DisplayName("the pointer wanders once the session has been idle")
    fun pointerWandersOnceTheSessionIsIdle() = runBlocking {
        val b4Driver = openDriver()
        assertGhostCursorIsActive()

        b4Driver.click("#far")
        val movesAfterClick = numberProbe(b4Driver, "window.__probe.moves.length")

        val drifted = waitUntil(IDLE_TIMEOUT_MILLIS) {
            numberProbe(b4Driver, "window.__probe.moves.length") > movesAfterClick
        }
        assertTrue(drifted, "the pointer did not drift within $IDLE_TIMEOUT_MILLIS ms of the last input")
        assertEquals(
            INSIDE_VIEWPORT,
            b4Driver.evaluateValue(LAST_MOVE_INSIDE_VIEWPORT)?.toString(),
            "an idle wander must stay inside the viewport",
        )
    }

    /** Open the fixture and wrap the driver the way every session does. */
    private suspend fun openDriver(): Browser4WebDriver {
        val raw = browser.newDriver().also { this.driver = it }
        raw.navigate(fixtureUrl)
        raw.waitForNavigation()
        raw.waitForSelector("#far")
        return Browser4WebDriver.from(raw as PulsarWebDriver)
    }

    private fun assertGhostCursorIsActive() {
        val raw = driver as PulsarWebDriver
        assertEquals(
            "ghostcursor",
            BrowserBehavior.humanizer(raw.settings.config).id,
            "the test pins the provider through a system property; the browser configuration did not see it",
        )
    }

    private suspend fun numberProbe(driver: Browser4WebDriver, expression: String): Double {
        val value = driver.evaluateValue(expression)
        return (value as? Number)?.toDouble() ?: error("expected a number from [$expression], got [$value]")
    }

    private suspend fun waitUntil(timeoutMillis: Long, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    companion object {
        private const val FIXTURE_PATH = "/ghostcursor-pointer-fixture.html"
        private const val IDLE_TIMEOUT_MILLIS = 10_000L
        private const val INSIDE = "INSIDE"
        private const val INSIDE_VIEWPORT = "INSIDE_VIEWPORT"

        /**
         * Pin the provider and the idle wander before the first browser — and the configuration
         * snapshot it reads — is created. The values are restored in [resetBehaviorProperties].
         */
        private val pinnedProperties = mapOf(
            B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER to "ghostcursor",
            B4Constants.BEHAVIOR_HUMANIZATION_IDLE_ENABLED to "true",
            B4Constants.BEHAVIOR_HUMANIZATION_IDLE_THRESHOLD_MS to "400",
        )

        private val previousValues = mutableMapOf<String, String?>()

        init {
            pinnedProperties.forEach { (key, value) ->
                previousValues[key] = System.getProperty(key)
                System.setProperty(key, value)
            }
        }

        @JvmStatic
        @AfterAll
        fun resetBehaviorProperties() {
            previousValues.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            BrowserBehavior.reset()
        }

        private const val EVERY_MOVE_IS_TRUSTED =
            "window.__probe.moves.every(function (m) { return m.trusted === true; })"

        private val MOUSE_DOWN_INSIDE_BUTTON = """
            (function () {
              var probe = window.__probe;
              var rect = document.getElementById('far').getBoundingClientRect();
              var inside = probe.downX >= rect.left - 1 && probe.downX <= rect.right + 1
                        && probe.downY >= rect.top - 1 && probe.downY <= rect.bottom + 1;
              var last = probe.moves.length ? probe.moves[probe.moves.length - 1] : null;
              return (inside ? 'INSIDE' : 'OUTSIDE')
                + ' down=' + probe.downX + ',' + probe.downY
                + ' rect=' + JSON.stringify(rect)
                + ' moves=' + probe.moves.length
                + ' lastMove=' + JSON.stringify(last);
            })()
        """.trimIndent()

        private val LAST_MOVE_INSIDE_VIEWPORT = """
            (function () {
              var moves = window.__probe.moves;
              if (!moves.length) { return 'NO_MOVES'; }
              var last = moves[moves.length - 1];
              var inside = last.x >= 0 && last.x <= window.innerWidth
                        && last.y >= 0 && last.y <= window.innerHeight;
              return inside ? 'INSIDE_VIEWPORT' : 'OUTSIDE_VIEWPORT ' + JSON.stringify(last);
            })()
        """.trimIndent()

        private val FIXTURE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <title>ghostcursor pointer fixture</title>
              <style>
                html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; }
                #far {
                  position: fixed; right: 40px; bottom: 40px;
                  width: 120px; height: 40px;
                  border: 1px solid #444; background: #fff;
                }
              </style>
            </head>
            <body>
              <button id="far" type="button">far</button>
              <script>
                window.__probe = { moves: [], down: -1, up: -1, downX: -1, downY: -1 };
                addEventListener('mousemove', function (e) {
                  window.__probe.moves.push({
                    x: e.clientX, y: e.clientY, t: performance.now(), trusted: e.isTrusted
                  });
                }, true);
                addEventListener('mousedown', function (e) {
                  window.__probe.down = performance.now();
                  window.__probe.downX = e.clientX;
                  window.__probe.downY = e.clientY;
                }, true);
                addEventListener('mouseup', function () {
                  window.__probe.up = performance.now();
                }, true);
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
