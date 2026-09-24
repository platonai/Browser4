package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.chrome.manage.PulsarBrowserFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * The navigator surface a bot detector reads, pinned against a real Chrome.
 *
 * These tests pin the browser4-side contract, not a detector's opinion:
 *
 * 1. **The headless token must never reach the wire.** A CDP-driven headless Chrome advertises
 *    `HeadlessChrome/<version>` by default, which is a one-line bot verdict (SannySoft
 *    `HEADCHR_UA`, Incolumitas `intoli.userAgent`, BrowserScan's aggregate "Robot" badge,
 *    "You are a bot!" on deviceandbrowserinfo) and contradicts the `Sec-CH-UA*` client hints the
 *    same browser sends. The launch-time `--user-agent` fix lives in [Browser4UserAgent] and is
 *    installed by `DefaultBrowserFactory.createStandardLaunchOptions()`; the unit tests pin the
 *    string it builds, this test proves that string reaches the page.
 * 2. **Values browser4 does not own must stay plausible.** `navigator.deviceMemory` and
 *    `navigator.maxTouchPoints` come from Chrome and the host, not from browser4. The assertions
 *    below encode the coherence rules real Chrome satisfies, so a future override that produces a
 *    raw host value (31.7 GB) or a touch count without a coarse pointer is caught here.
 *
 * This is the 4.14.x home of the `NavigatorStealthIT` that lived in `browser4-tests/pulsar-it-tests`
 * (that module — and its `WebDriverTestBase` — no longer exists here). The fixture is a page served
 * by the JDK HTTP server on `127.0.0.1`, which Chrome treats as a potentially trustworthy origin,
 * so the [SecureContext]-only `navigator.userAgentData` and `navigator.deviceMemory` are exposed and
 * the test needs neither an external site nor a shared test module.
 *
 * Tagged [ManualOnly] (plus `RequiresBrowser` / `E2E`), so neither a plain `mvn test` nor CI
 * launches a browser for it. Run it explicitly, against headless Chrome (the default display mode):
 *
 * ```bash
 * mvn test -pl browser4-core/browser4-browser \
 *     -Dtest=NavigatorStealthE2ETest -D"surefire.excludedGroups="
 * ```
 */
@Tag("E2E")
@Tag("RequiresBrowser")
@Tag("ManualOnly")
@DisplayName("Navigator stealth surface (real browser)")
class NavigatorStealthE2ETest {

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
    @DisplayName("the user agent never advertises the headless token")
    fun userAgentNeverAdvertisesTheHeadlessToken() = runBlocking {
        val driver = openFixture()

        val userAgent = driver.evaluate("navigator.userAgent", "")
        println("navigator.userAgent = $userAgent")

        assertTrue(userAgent.isNotEmpty(), "navigator.userAgent must not be empty")
        assertFalse(
            HEADLESS_TOKEN.containsMatchIn(userAgent),
            "the user agent still advertises the headless token: $userAgent"
        )
        assertTrue(
            REDUCED_CHROME_TOKEN.containsMatchIn(userAgent),
            "expected Chrome's reduced product token (Chrome/<major>.0.0.0): $userAgent"
        )
    }

    @Test
    @DisplayName("the client hints agree with the user agent")
    fun clientHintsAgreeWithTheUserAgent() = runBlocking {
        val driver = openFixture()

        val brands = driver.evaluate(
            "navigator.userAgentData ? navigator.userAgentData.brands.map(b => b.brand).join(', ') : ''",
            ""
        )
        println("navigator.userAgentData.brands = $brands")

        // A blank result means the page is not a secure context, where userAgentData is not exposed.
        if (brands.isEmpty()) {
            return@runBlocking
        }

        assertFalse(
            HEADLESS_TOKEN.containsMatchIn(brands),
            "the client hints still advertise the headless token: $brands"
        )
        assertTrue(
            brands.contains("Google Chrome"),
            "expected the Chrome brand the reduced user agent claims: $brands"
        )
    }

    @Test
    @DisplayName("device memory is a plausible Chrome bucket, never a raw host value")
    fun deviceMemoryIsAPlausibleChromeBucket() = runBlocking {
        val driver = openFixture()

        val deviceMemory = driver.evaluate("navigator.deviceMemory", 0.0)
        println("navigator.deviceMemory = $deviceMemory")

        // `navigator.deviceMemory` is [SecureContext]; undefined maps to the 0.0 default here.
        if (deviceMemory <= 0.0) {
            return@runBlocking
        }

        // Chrome derives the value from the host's RAM by rounding down to a power of two, so a
        // value off the power-of-two ladder means something replaced Chrome's own computation.
        val exponent = Math.log(deviceMemory) / Math.log(2.0)
        assertEquals(
            Math.floor(exponent), exponent, 1e-9,
            "navigator.deviceMemory must be a power of two, got $deviceMemory"
        )
        assertTrue(deviceMemory >= 0.25, "navigator.deviceMemory is below the API minimum: $deviceMemory")
    }

    @Test
    @DisplayName("touch signals are internally coherent")
    fun touchSignalsAreCoherent() = runBlocking {
        val driver = openFixture()

        val maxTouchPoints = driver.evaluate("navigator.maxTouchPoints", 0)
        val anyCoarse = driver.evaluate("matchMedia('(any-pointer: coarse)').matches", false)
        val coarse = driver.evaluate("matchMedia('(pointer: coarse)').matches", false)
        val hasTouchEvent = driver.evaluate("('ontouchstart' in window)", false)
        println(
            "touch surface: maxTouchPoints=$maxTouchPoints, any-pointer:coarse=$anyCoarse, " +
                "pointer:coarse=$coarse, ontouchstart=$hasTouchEvent"
        )

        assertTrue(maxTouchPoints >= 0, "navigator.maxTouchPoints must not be negative: $maxTouchPoints")
        // A device that reports touch points must expose a coarse pointer somewhere, otherwise the
        // two readings describe different hardware.
        if (maxTouchPoints > 0) {
            assertTrue(
                anyCoarse || hasTouchEvent,
                "maxTouchPoints=$maxTouchPoints but no coarse pointer and no touch event support"
            )
        }
        // A coarse primary pointer implies a coarse pointer somewhere.
        if (coarse) {
            assertTrue(anyCoarse, "'(pointer: coarse)' is true while '(any-pointer: coarse)' is false")
        }
    }

    /** Open the fixture page and keep the driver for teardown. */
    private suspend fun openFixture(): WebDriver {
        val driver = browser.newDriver().also { this.driver = it }
        driver.navigate(fixtureUrl)
        driver.waitForNavigation()
        driver.waitForSelector("body")
        return driver
    }

    companion object {
        private const val FIXTURE_PATH = "/navigator-fixture.html"

        /** `HeadlessChrome`, `Headless`, ... — any spelling of the token fails a detector. */
        private val HEADLESS_TOKEN = Regex("Headless", RegexOption.IGNORE_CASE)

        /** The reduced product token Chrome reports after user-agent reduction: `Chrome/153.0.0.0`. */
        private val REDUCED_CHROME_TOKEN = Regex("""Chrome/\d+\.0\.0\.0""")

        private val FIXTURE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><title>Navigator fixture</title></head>
            <body>
              <h1>Navigator fixture</h1>
              <p>The page only has to exist: the assertions read the navigator surface of a real
                 Chrome, not anything this document provides.</p>
            </body>
            </html>
        """.trimIndent()
    }
}
