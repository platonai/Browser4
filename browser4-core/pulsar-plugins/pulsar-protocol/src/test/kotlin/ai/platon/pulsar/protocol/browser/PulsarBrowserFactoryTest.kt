package ai.platon.pulsar.protocol.browser

import ai.platon.browser4.protocol.browser.PulsarBrowserFactory
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PulsarBrowserFactoryTest {

    private lateinit var browserFactory: PulsarBrowserFactory
    private val browsers = mutableListOf<Browser>()

    @BeforeEach
    fun setUp() {
        browserFactory = PulsarBrowserFactory()
    }

    @AfterEach
    fun tearDown() {
        browsers.forEach { it.close() }
    }

    @Test
    fun testConnect() {

    }

    @Disabled("Chrome DevTools remote debugging requires a non-default data directory.")
    @Test
    fun testLaunchSystemDefaultBrowser() {
        val browser = browserFactory.launchSystemDefaultBrowser()
        browsers.add(browser)
        assertEquals(BrowserId.SYSTEM_DEFAULT, browser.id)
        assertEquals(BrowserId.SYSTEM_DEFAULT.contextDir, browser.id.contextDir)
    }

    @Test
    fun testLaunchDefaultBrowser() {
        val browser = browserFactory.launchDefaultBrowser()
        browsers.add(browser)
        assertEquals(BrowserId.DEFAULT, browser.id)
        assertEquals(BrowserId.DEFAULT.contextDir, browser.id.contextDir)
    }

    @Test
    fun testLaunchPrototypeBrowser() {
        val browser = browserFactory.launchPrototypeBrowser()
        browsers.add(browser)
        assertEquals(BrowserId.PROTOTYPE, browser.id)
        assertEquals(BrowserId.PROTOTYPE.contextDir, browser.id.contextDir)
    }

    @Test
    fun testLaunchNextSequentialBrowser() {
        val browser1 = browserFactory.launchNextSequentialBrowser()
        browsers.add(browser1)

        val browser2 = browserFactory.launchNextSequentialBrowser()
        browsers.add(browser2)

        assertTrue { browser1.id.contextDir.toString().replace("\\", "/").contains("context/groups/default") }
        assertTrue(
            "Context dir should be start with AppPaths.CONTEXT_GROUP_BASE_DIR\n" +
                    "${browser1.id.contextDir}\n${AppPaths.CONTEXT_GROUP_BASE_DIR}"
        )
        {
            browser1.id.contextDir.startsWith(AppPaths.CONTEXT_GROUP_BASE_DIR)
        }
        assertTrue(
            "Context dir should be start with AppPaths.getContextGroupDir(\"default\")\n" +
                    "${browser1.id.contextDir}\n" +
                    "${AppPaths.getContextGroupDir("default")}"
        )
        {
            browser1.id.contextDir.startsWith(AppPaths.getContextGroupDir("default"))
        }
    }

    @Test
    fun testLaunchRandomTempBrowser() {
        val browser = browserFactory.launchRandomTempBrowser()
        browsers.add(browser)

        assertTrue { browser.id.contextDir.toString().replace("\\", "/").contains("context/tmp/groups/rand") }
        assertTrue("Context dir should be start with AppPaths.CONTEXT_TMP_DIR\n${browser.id.contextDir}\n${AppPaths.CONTEXT_TMP_DIR}") {
            browser.id.contextDir.startsWith(AppPaths.CONTEXT_TMP_DIR)
        }
        assertTrue(
            "Context dir should be start with AppPaths.getTmpContextGroupDir(\"rand\")\n" +
                    "${browser.id.contextDir}\n" +
                    "${AppPaths.getTmpContextGroupDir("rand")}"
        ) {
            browser.id.contextDir.startsWith(AppPaths.getTmpContextGroupDir("rand"))
        }
    }
}
