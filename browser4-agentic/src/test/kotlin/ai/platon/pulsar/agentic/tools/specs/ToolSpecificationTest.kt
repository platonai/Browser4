package ai.platon.pulsar.agentic.tools.specs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ToolSpecificationTest {

    @Test
    @DisplayName("tab domain is a browser interaction")
    fun testTabDomainIsBrowserInteraction() {
        assertTrue(ToolSpecification.isBrowserInteraction("tab"))
    }

    @Test
    @DisplayName("browser domain is a browser interaction")
    fun testBrowserDomainIsBrowserInteraction() {
        assertTrue(ToolSpecification.isBrowserInteraction("browser"))
    }

    @Test
    @DisplayName("fs domain is not a browser interaction")
    fun testFsDomainIsNotBrowserInteraction() {
        assertFalse(ToolSpecification.isBrowserInteraction("fs"))
    }

    @Test
    @DisplayName("agent domain is not a browser interaction")
    fun testAgentDomainIsNotBrowserInteraction() {
        assertFalse(ToolSpecification.isBrowserInteraction("agent"))
    }

    @Test
    @DisplayName("system domain is not a browser interaction")
    fun testSystemDomainIsNotBrowserInteraction() {
        assertFalse(ToolSpecification.isBrowserInteraction("system"))
    }

    @Test
    @DisplayName("null domain defaults to browser interaction for safety")
    fun testNullDomainDefaultsToBrowserInteraction() {
        assertTrue(ToolSpecification.isBrowserInteraction(null))
    }

    @Test
    @DisplayName("blank domain defaults to browser interaction for safety")
    fun testBlankDomainDefaultsToBrowserInteraction() {
        assertTrue(ToolSpecification.isBrowserInteraction(""))
        assertTrue(ToolSpecification.isBrowserInteraction("  "))
    }

    @Test
    @DisplayName("domain matching is case-insensitive")
    fun testDomainMatchingIsCaseInsensitive() {
        assertTrue(ToolSpecification.isBrowserInteraction("TAB"))
        assertTrue(ToolSpecification.isBrowserInteraction("Tab"))
        assertTrue(ToolSpecification.isBrowserInteraction("BROWSER"))
    }

    @Test
    @DisplayName("unknown custom domain is not a browser interaction")
    fun testUnknownCustomDomainIsNotBrowserInteraction() {
        assertFalse(ToolSpecification.isBrowserInteraction("skill.debug.scraping"))
        assertFalse(ToolSpecification.isBrowserInteraction("custom"))
    }

    @Test
    @DisplayName("BROWSER_INTERACTION_DOMAINS contains expected domains")
    fun testBrowserInteractionDomainsContainsExpectedDomains() {
        assertTrue(ToolSpecification.BROWSER_INTERACTION_DOMAINS.contains("tab"))
        assertTrue(ToolSpecification.BROWSER_INTERACTION_DOMAINS.contains("browser"))
        assertFalse(ToolSpecification.BROWSER_INTERACTION_DOMAINS.contains("fs"))
        assertFalse(ToolSpecification.BROWSER_INTERACTION_DOMAINS.contains("agent"))
        assertFalse(ToolSpecification.BROWSER_INTERACTION_DOMAINS.contains("system"))
    }
}
