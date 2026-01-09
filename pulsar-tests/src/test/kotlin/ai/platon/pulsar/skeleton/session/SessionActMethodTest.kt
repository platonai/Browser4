package ai.platon.pulsar.skeleton.session

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.skeleton.ai.tta.TextToActionTestBase
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.util.server.EnableMockServerApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Order(1100)
@Tag("ExternalResourceDependent")
@Tag("TimeConsumingTest")
@Ignore("Takes very long time, run it manually.")
@SpringBootTest(
    classes = [EnableMockServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT
)
class SessionActMethodTest : TextToActionTestBase() {

    private val demoUrl get() = "$ttaBaseURL/act/act-demo.html"

    // Resources initialized per test
    private lateinit var driver: WebDriver

    private val agent get() = session.companionAgent

    @BeforeEach
    fun setUp() {
        runBlocking {
            driver = session.createBoundDriver()
            // Open demo page so each test begins from consistent state
            session.open(demoUrl)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    /**
     * Utility to wait for a condition with a timeout, reduces flakiness of dynamic actions.
     */
    private suspend fun waitUntil(
        timeoutMs: Long = 6000,
        intervalMs: Long = 200,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    /**
     * Mirrors step: open URL and basic parse / extract title.
     */
    @Test
    fun testOpenAndParseDemoPage() = runBlocking {
        val page = session.capture(driver, demoUrl)
        val document = session.parse(page)
        val title = document.selectFirstTextOrNull("#title")
        assertEquals("Session Instructions Demo", title, "Initial #title text should match")
    }

    /**
     * Mirrors action: "search for 'browser'" and validates dynamic results appear.
     */
    @Test
    fun testSearchActionShowsResults() = runBlocking {
        agent.run("search for 'browser'")
        val appeared = waitUntil { driver.selectFirstTextOrNull("#searchResults")?.contains("browser") == true }
        assertTrue(appeared, "Search results should appear and contain query text 'browser'")
    }

    /**
     * Mirrors action: "click the 3rd link" which should navigate to pageC.html with title Page C (Third Link Target)
     */
    @Test
    fun testClickThirdLinkNavigatesToPageC() = runBlocking {
        agent.act(ActionOptions("click the 3rd link"))
        val remainingTime = driver.waitUntil { driver.currentUrl().endsWith("pageC.html") }
        // val navigated = waitUntil { driver.currentUrl().endsWith("pageC.html") }
        assertTrue(remainingTime.seconds > 0, "Should navigate to pageC.html after clicking 3rd link")
        val page = session.capture(driver)
        val doc = session.parse(page)
        assertEquals("Page C (Third Link Target)", doc.selectFirstTextOrNull("#title"))
    }

    /**
     * Mirrors action: "click the first link that contains 'Show HN' or 'Ask HN'" expecting page2.html.
     */
    @Test
    fun testClickShowHNLikeLink() = runBlocking {
        agent.act(ActionOptions("click the first link that contains 'Show HN' or 'Ask HN'"))
        val navigated = waitUntil { driver.currentUrl().endsWith("page2.html") }
        assertTrue(navigated, "Should navigate to page2.html (Show HN: Demo Project)")
        val page = session.capture(driver)
        val doc = session.parse(page)
        assertEquals("Show HN: Demo Project", doc.selectFirstTextOrNull("#title"))
    }

    /**
     * Mirrors actions: click link -> navigate back -> navigate forward.
     */
    @Test
    fun testNavigationBackAndForward() {
        runBlocking {
            agent.act(ActionOptions("click the 5th link"))
            assertTrue(
                waitUntil { driver.currentUrl().endsWith("pageE.html") },
                "Should reach pageE.html, actual ${driver.currentUrl()}"
            )

            agent.act(ActionOptions("navigate back"))
            assertTrue(
                waitUntil { driver.currentUrl().endsWith("act-demo.html") },
                "Should navigate back to demo page, actual ${driver.currentUrl()}"
            )

            agent.act(ActionOptions("navigate forward"))
            assertTrue(
                waitUntil { driver.currentUrl().endsWith("pageE.html") },
                "Should navigate forward to pageE.html, actual ${driver.currentUrl()}"
            )
        }
    }

    /**
     * Mirrors action: "extract article titles and their hrefs from the main list".
     * Since the agent returns a PerceptiveAgent with a history, we assert key tokens appear there.
     */
    @Test
    fun testExtractArticleTitles() = runBlocking {
        agent.run("extract article titles and their hrefs from the main list")
        val history = agent.stateHistory.states.joinToString("\n")
        printlnPro(history)
        assertTrue(
            history.contains("Show HN") || history.contains("Ask HN"),
            "History should contain extracted article titles"
        )
    }
}
