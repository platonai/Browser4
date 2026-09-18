package ai.platon.pulsar.rest.session

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("BrowserIdentity UA parsing")
class BrowserIdentityTest {

    @Test
    fun `parses Chrome desktop UA`() {
        val id = BrowserIdentity.parse(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )
        assertEquals("chrome", id.family)
        assertEquals("Google Chrome", id.name)
        assertEquals("138.0.0.0", id.version)
        assertEquals("chrome", id.family)
    }

    @Test
    fun `parses Edge desktop UA as edge not chrome`() {
        // Edge UAs carry Chrome AND Safari tokens — Edge markers must win.
        val id = BrowserIdentity.parse(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0"
        )
        assertEquals("edge", id.family)
        assertEquals("Microsoft Edge", id.name)
        assertEquals("138.0.0.0", id.version)
    }

    @Test
    fun `parses Edge Android and iOS UAs`() {
        assertEquals("edge", BrowserIdentity.parse("Mozilla/5.0 ... Chrome/138.0.0.0 Mobile Safari/537.36 EdgA/138.0.0.0").family)
        assertEquals("edge", BrowserIdentity.parse("Mozilla/5.0 ... Version/17.0 Mobile/15E148 Safari/604.1 EdgiOS/138.0.0.0").family)
    }

    @Test
    fun `parses chromium forks conservatively`() {
        val brave = BrowserIdentity.parse("Mozilla/5.0 ... Chrome/138.0.0.0 Safari/537.36 Brave/1.68.0")
        assertEquals("chromium-other", brave.family)
        assertEquals("Brave", brave.name)

        val opera = BrowserIdentity.parse("Mozilla/5.0 ... Chrome/138.0.0.0 Safari/537.36 OPR/124.0.0.0")
        assertEquals("chromium-other", opera.family)
        assertEquals("Opera", opera.name)
    }

    @Test
    fun `empty and unknown UAs fall back safely`() {
        val empty = BrowserIdentity.parse(null)
        assertEquals("unknown", empty.family)
        assertNull(empty.rawUa)

        val weird = BrowserIdentity.parse("totally-not-a-browser")
        assertEquals("unknown", weird.family)
        assertEquals("totally-not-a-browser", weird.rawUa)
    }
}
