package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.browser.BrowserType
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class PrivacyAgentTests {
    private val contextPathBase = Files.createTempDirectory("test-")
    private val contextPath = contextPathBase.resolve("cx.5kDMDS2")
    private val contextPath2 = contextPathBase.resolve("cx.7KmtAC2")

    @Test
    fun testPrivacyAgentComparison() {
        val id = BrowserProfile(contextPath, BrowserType.PULSAR_CHROME)
        val id2 = BrowserProfile(contextPath2, BrowserType.PLAYWRIGHT_CHROME)
        assertNotEquals(id, id2)
        assertNotEquals(id.hashCode(), id2.hashCode())
        assertTrue { id < id2 }
        assertTrue { id.toString().contains(contextPath.toString()) }
    }

    @Test
    fun testPrivacyAgentEquality() {
        val id = BrowserProfile(contextPath, BrowserType.PULSAR_CHROME)
        val id2 = BrowserProfile(contextPath, BrowserType.PULSAR_CHROME)
        assertEquals(id, id2)
        assertEquals(id.hashCode(), id2.hashCode())
        assertTrue { id == id2 }

        val activeContexts = ConcurrentHashMap<BrowserProfile, Any>()
        activeContexts[id] = 1
        assertTrue { activeContexts.containsKey(id) }
        assertTrue { activeContexts.containsKey(id2) }
    }

    @Test
    fun testPrivacyAgentContains() {
        val activeContexts = ConcurrentHashMap<BrowserProfile, Any>()
        val id = BrowserProfile(contextPath, BrowserType.PULSAR_CHROME)
        val id2 = BrowserProfile(contextPath, BrowserType.PLAYWRIGHT_CHROME)
        activeContexts[id] = 1
        assertTrue { activeContexts.containsKey(id) }
        assertFalse { activeContexts.containsKey(id2) }

        activeContexts.remove(id)
        assertFalse { activeContexts.containsKey(id) }

        activeContexts.clear()
        assertTrue { activeContexts.isEmpty() }
        activeContexts.computeIfAbsent(id) { 0 }
        assertTrue { activeContexts.containsKey(id) }
        activeContexts.computeIfAbsent(id) { 0 }
        assertTrue { activeContexts.containsKey(id) }
    }
}
