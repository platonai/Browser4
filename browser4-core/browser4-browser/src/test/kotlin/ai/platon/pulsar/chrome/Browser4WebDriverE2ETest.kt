package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.chrome.manage.PulsarBrowserFactory
import ai.platon.pulsar.common.urls.URLUtils
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
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
 * Driver-level capture-annotation tests against a real Chrome.
 *
 * [Browser4WebDriver.pageSource] and [Browser4WebDriver.outerHTML] must annotate
 * the HTML they serialize: the `vi` (visual-information) bounding boxes and the
 * page URL produced by the normalizer the session installs.  While the runtime's
 * `_viDataComputed` flag is false the serializer silently degrades to plain
 * `outerHTML`, which is what made `htmlsnapshot` exports unusable for offline
 * consumers (issue #588) — this test pins the behaviour down on the live
 * document, without the session or REST layer.
 *
 * The page is a fixture served by the JDK HTTP server on an ephemeral port, so
 * the test needs no external site and no shared test module.
 *
 * Tagged [ManualOnly] (plus `RequiresBrowser` / `E2E`), so neither a plain
 * `mvn test` nor CI launches a browser for it.  Run it explicitly:
 *
 * ```bash
 * mvn test -pl browser4-core/browser4-browser \
 *     -Dtest=Browser4WebDriverE2ETest -D"surefire.excludedGroups="
 * ```
 */
@Tag("E2E")
@Tag("RequiresBrowser")
@Tag("ManualOnly")
@DisplayName("Browser4WebDriver capture annotations (real browser)")
class Browser4WebDriverE2ETest {

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
    @DisplayName("pageSource annotates a merely navigated page with vi boxes and the page URL")
    fun pageSourceAnnotatesViDataAndPageUrl() = runBlocking {
        val b4Driver = openAnnotatedDriver()

        // The session installs its normalization policy when it binds a driver
        // (AbstractPulsarSession.bindDriver); this test has no session, so it
        // installs the underlying URL normalization the policy is built on.
        b4Driver.pageUrlNormalizer = { url -> URLUtils.normalizeOrEmpty(url).takeIf { it.isNotBlank() } }

        assertFalse(
            isViDataComputed(b4Driver),
            "the fixture must not be annotated before the driver is asked for its HTML"
        )

        val pageSource = b4Driver.pageSource() ?: ""

        assertTrue(
            VI_PATTERN.containsMatchIn(pageSource),
            "pageSource() must compute the vi data on demand | ${pageSource.take(500)}"
        )
        assertTrue(
            pageSource.contains("PulsarMetaInformation"),
            "pageSource() must carry the runtime metadata element | ${pageSource.take(500)}"
        )

        val pageUrl = NORMALIZED_URI_PATTERN.find(pageSource)?.groupValues?.get(1).orEmpty()
        assertEquals(
            URLUtils.normalizeOrEmpty(fixtureUrl),
            pageUrl,
            "pageSource() must record the normalized page URL | ${pageSource.take(500)}"
        )
    }

    @Test
    @DisplayName("outerHTML annotates the document, and a subtree without the page link")
    fun outerHTMLAnnotatesDocumentAndSubtree() = runBlocking {
        val b4Driver = openAnnotatedDriver()
        b4Driver.pageUrlNormalizer = { url -> URLUtils.normalizeOrEmpty(url).takeIf { it.isNotBlank() } }

        val document = b4Driver.outerHTML() ?: ""
        assertTrue(
            document.contains("vi=\""),
            "outerHTML() must compute the vi data on demand | ${document.take(500)}"
        )
        assertTrue(
            document.contains("normalizedURI"),
            "outerHTML() must record the normalized page URL | ${document.take(500)}"
        )

        val body = b4Driver.outerHTML("body") ?: ""
        assertTrue(
            body.contains("vi=\""),
            "outerHTML('body') must carry the vi attributes | ${body.take(300)}"
        )
        // The page URL describes the document, not a fragment: the serializer
        // only writes the link into a serialized <head>.
        assertFalse(
            body.contains("normalizedURI"),
            "outerHTML('body') must not carry the page link | ${body.take(300)}"
        )
    }

    /** Open the fixture and wrap the driver the way every session does. */
    private suspend fun openAnnotatedDriver(): Browser4WebDriver {
        val driver = browser.newDriver().also { this.driver = it }
        driver.navigate(fixtureUrl)
        driver.waitForNavigation()
        driver.waitForSelector("body")
        waitForPulsarUtils(driver)
        return Browser4WebDriver.from(driver as PulsarWebDriver)
    }

    /**
     * Wait until the Browser4 runtime is registered on the tab.  The isolated
     * world is created asynchronously after the frame commits, so an immediate
     * probe can still see the main world.
     */
    private suspend fun waitForPulsarUtils(driver: WebDriver, attempts: Int = 40, delayMillis: Long = 150) {
        repeat(attempts) { attempt ->
            if (driver.evaluateValue("typeof(__pulsar_utils__)")?.toString() == "function") return
            if (attempt < attempts - 1) delay(delayMillis)
        }
        assertEquals(
            "function",
            driver.evaluateValue("typeof(__pulsar_utils__)")?.toString(),
            "__pulsar_utils__ is not injected properly"
        )
    }

    private suspend fun isViDataComputed(driver: Browser4WebDriver): Boolean =
        driver.evaluateValue("window.__pulsar_utils__?._viDataComputed") as? Boolean ?: false

    companion object {
        private const val FIXTURE_PATH = "/annotations-fixture.html"

        /** `vi` values: base-36 (current) or the legacy space-separated decimal form. */
        private val VI_PATTERN = Regex(
            """\bvi="([0-9a-z]+,[0-9a-z]+,[0-9a-z]+,[0-9a-z]+|\d+(?:\.\d+)?\s+\d+(?:\.\d+)?\s+\d+(?:\.\d+)?\s+\d+(?:\.\d+)?)""""
        )

        private val NORMALIZED_URI_PATTERN = Regex("""<link rel="normalizedURI" href="([^"]*)"""")

        private val FIXTURE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8"><title>Capture annotation fixture</title></head>
            <body>
              <h1>Capture annotation fixture</h1>
              <p>A paragraph with enough text to be worth a bounding box, so the runtime
                 has content nodes to measure while it computes the visual information.</p>
              <p><a href="/one">First link</a> and <a href="/two">second link</a>.</p>
              <form>
                <input type="text" name="query" value="probe">
                <button type="button">Submit</button>
              </form>
              <img alt="pixel" width="32" height="32"
                   src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7">
            </body>
            </html>
        """.trimIndent()
    }
}
