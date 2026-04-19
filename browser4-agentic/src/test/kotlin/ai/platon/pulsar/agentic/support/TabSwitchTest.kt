package ai.platon.pulsar.agentic.support

import ai.platon.pulsar.agentic.common.SimpleKotlinParser
import ai.platon.browser4.driver.chrome.dom.model.BrowserState
import ai.platon.browser4.driver.chrome.dom.model.ClientInfo
import ai.platon.browser4.driver.chrome.dom.model.ScrollState
import ai.platon.browser4.driver.chrome.dom.model.TabState
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.util.Locale
import org.junit.jupiter.api.DisplayName

class TabSwitchTest {

    private val mapper = jacksonObjectMapper()

    private fun createTestClientInfo() = ClientInfo(
        timeZone = "UTC",
        locale = Locale.US,
        viewportWidth = 1920,
        viewportHeight = 1080,
        screenWidth = 1920,
        screenHeight = 1080
    )

    private fun createTestScrollState() = ScrollState(
        x = 0.0,
        y = 0.0,
        viewport = Dimension(1920, 1080),
        totalHeight = 2000.0,
        scrollYRatio = 0.5,
    )

    @Test
        @DisplayName("TabState serialization includes all fields")
    fun tabstateSerializationIncludesAllFields() {
        val tabState = TabState(
            id = "tab-1",
            driverId = 42,
            url = "https://example.com",
            title = "Example Domain",
            active = true
        )

        val json = mapper.writeValueAsString(tabState)
        assertNotNull(json)
        assertTrue(json.contains("\"id\":\"tab-1\""))
        assertTrue(json.contains("\"driverId\":42"))
        assertTrue(json.contains("\"url\":\"https://example.com\""))
        assertTrue(json.contains("\"title\":\"Example Domain\""))
        assertTrue(json.contains("\"active\":true"))
    }

    @Test
        @DisplayName("BrowserState with tabs serialization")
    fun browserstateWithTabsSerialization() {
        val tabs = listOf(
            TabState("tab-1", 1, "https://example.com", "Example", true),
            TabState("tab-2", 2, "https://google.com", "Google", false)
        )

        val browserState = BrowserState(
            url = "https://example.com",
            goBackUrl = null,
            goForwardUrl = null,
            clientInfo = createTestClientInfo(),
            scrollState = createTestScrollState(),
            tabs = tabs,
            activeTabId = "tab-1"
        )

        val json = mapper.writeValueAsString(browserState)
        assertNotNull(json)
        assertTrue(json.contains("\"tabs\""))
        assertTrue(json.contains("\"activeTabId\":\"tab-1\""))
        assertTrue(json.contains("tab-1"))
        assertTrue(json.contains("tab-2"))
    }

    @Test
        @DisplayName("parse browser switchTab call")
    fun parseBrowserSwitchtabCall() {
        val tc = SimpleKotlinParser().parseFunctionExpression("browser.switchTab(\"tab-1\")")
        assertNotNull(tc)
        tc!!
        assertEquals("browser", tc.domain)
        assertEquals("switchTab", tc.method)
        assertEquals("tab-1", tc.arguments["0"])
    }

    @Test
        @DisplayName("parse browser switchTab with numeric id")
    fun parseBrowserSwitchtabWithNumericId() {
        // Even though the signature now uses String, test parsing of numeric-looking IDs
        val tc = SimpleKotlinParser().parseFunctionExpression("browser.switchTab(\"123\")")
        assertNotNull(tc)
        tc!!
        assertEquals("browser", tc.domain)
        assertEquals("switchTab", tc.method)
        assertEquals("123", tc.arguments["0"])
    }

    @Test
        @DisplayName("BrowserState with empty tabs list")
    fun browserstateWithEmptyTabsList() {
        val browserState = BrowserState(
            url = "https://example.com",
            goBackUrl = null,
            goForwardUrl = null,
            clientInfo = createTestClientInfo(),
            scrollState = createTestScrollState(),
            tabs = emptyList(),
            activeTabId = null
        )

        val json = mapper.writeValueAsString(browserState)
        assertNotNull(json)
        assertTrue(json.contains("\"tabs\":[]"))
        assertTrue(json.contains("\"activeTabId\":null"))
    }

    @Test
        @DisplayName("TabState with minimal fields")
    fun tabstateWithMinimalFields() {
        val tabState = TabState(
            id = "tab-minimal",
            url = "https://minimal.com"
        )

        val json = mapper.writeValueAsString(tabState)
        assertNotNull(json)
        assertTrue(json.contains("\"id\":\"tab-minimal\""))
        assertTrue(json.contains("\"url\":\"https://minimal.com\""))
        // Optional fields should be null/false
        assertTrue(json.contains("\"driverId\":null") || json.contains("\"driverId\" : null"))
        assertTrue(json.contains("\"active\":false"))
    }
}
