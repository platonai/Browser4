package ai.platon.pulsar.skeleton.crawl.fetch

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId.Companion.SYSTEM_DEFAULT
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.PrivacyContext
import java.nio.file.Files
import kotlin.test.*

class BrowserIdTests {
    private val contextPath = Files.createTempDirectory("test-")

    @AfterTest
    fun tearDown() {
        Files.deleteIfExists(contextPath)
    }

    @Test
    fun testBrowserComparison() {
        val id = BrowserId(contextPath, BrowserType.PULSAR_CHROME)
        val id2 = BrowserId(contextPath, BrowserType.PLAYWRIGHT_CHROME)
        assertNotEquals(id, id2)
        assertNotEquals(id.hashCode(), id2.hashCode())
        assertTrue("id.browserType.toString() > id2.browserType.toString()") { id.browserType.toString() > id2.browserType.toString() }
        assertTrue("id.browserType < id2.browserType") { id.browserType < id2.browserType }
        assertTrue("id.fingerprint < id2.fingerprint") { id.fingerprint > id2.fingerprint }
        assertTrue("id > id2") { id > id2 }
        assertTrue("contains") { id.toString().contains(contextPath.toString()) }
        assertTrue("startsWith") { id.userDataDir.startsWith(contextPath) }
    }

    @Test
    fun testPrototypeBrowserId() {
        val id = BrowserId.PROTOTYPE
        printlnPro(id)
        printlnPro(id.contextDir)
        printlnPro(id.userDataDir)
        assertTrue { id.userDataDir.toString().contains("google-chrome") }
        assertEquals(id.contextDir, PrivacyContext.PROTOTYPE_CONTEXT_DIR)
        assertEquals(id.userDataDir, PrivacyContext.PROTOTYPE_DATA_DIR)
        assertTrue { id.userDataDir.startsWith(PrivacyContext.PROTOTYPE_DATA_DIR) }
    }

    @Test
    fun testDefaultBrowserId() {
        val id = BrowserId.DEFAULT
        printlnPro(id)
        printlnPro(id.contextDir)
        printlnPro(id.userDataDir)
        assertTrue { id.userDataDir.toString().contains("PULSAR_CHROME") }
        assertEquals(id.contextDir, PrivacyContext.DEFAULT_CONTEXT_DIR)
        assertEquals(id.userDataDir, PrivacyContext.DEFAULT_CONTEXT_DIR.resolve(BrowserType.PULSAR_CHROME.name))
        assertTrue { id.userDataDir.startsWith(PrivacyContext.DEFAULT_CONTEXT_DIR.resolve(BrowserType.PULSAR_CHROME.name)) }
    }

    @Test
    fun testSystemDefaultBrowserId() {
        val id = SYSTEM_DEFAULT
        printlnPro(id)
        printlnPro(id.contextDir)
        printlnPro(id.userDataDir)
        assertFalse { id.userDataDir.toString().contains("PULSAR_CHROME") }
        assertEquals(id.contextDir, AppPaths.SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER)
        assertTrue { id.userDataDir.startsWith(AppPaths.SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER) }
        assertEquals(id.userDataDir, AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER)
    }

    @Test
    fun testNextSequentialBrowserId() {
        IntRange(1, 20).forEach { i ->
            val id = BrowserId.NEXT_SEQUENTIAL
            val expectedContextBaseDir = AppPaths.CONTEXT_GROUP_BASE_DIR
            printlnPro("\nRound $i")
            printlnPro("Browser Id: $id")
            printlnPro("contextDir: " + id.contextDir)
            printlnPro("userDataDir: " + id.userDataDir)
            assertTrue("Actual: ${id.userDataDir}") { id.userDataDir.toString().contains("PULSAR_CHROME") }
            assertTrue("$expectedContextBaseDir <- ${id.userDataDir}") {
                id.userDataDir.startsWith(expectedContextBaseDir) }
        }
    }
}

