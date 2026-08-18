package ai.platon.pulsar.skeleton.session

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_DISPLAY_MODE
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.PulsarSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the session-level browser launch behavior introduced to fix
 * `open --headed` launching into a headless browser.
 *
 * The session's explicit display mode (e.g. `headed=true` from
 * `open --headed`) must reach the actual Chrome launch. See
 * [AbstractPulsarSession.createBoundDriver].
 */
class AbstractPulsarSessionTest {

    // ------------------------------------------------------------------
    // browserIdFor: profile mode -> BrowserId mapping (mirrors pulsar's
    // AbstractBrowserFactory.launch(profileMode))
    // ------------------------------------------------------------------

    @Test
    fun `browserIdFor maps SYSTEM_DEFAULT to SYSTEM_DEFAULT`() {
        val id = AbstractPulsarSession.browserIdFor(BrowserProfileMode.SYSTEM_DEFAULT)
        assertTrue(id.profile.isSystemDefault, "expected system-default profile but was ${id.profile.ident}")
    }

    @Test
    fun `browserIdFor maps DEFAULT to DEFAULT`() {
        val id = AbstractPulsarSession.browserIdFor(BrowserProfileMode.DEFAULT)
        assertTrue(id.profile.isDefault, "expected default profile but was ${id.profile.ident}")
    }

    @Test
    fun `browserIdFor maps PROTOTYPE to PROTOTYPE`() {
        val id = AbstractPulsarSession.browserIdFor(BrowserProfileMode.PROTOTYPE)
        assertTrue(id.profile.isPrototype, "expected prototype profile but was ${id.profile.ident}")
    }

    @Test
    fun `browserIdFor maps SEQUENTIAL to a permanent group profile`() {
        // NEXT_SEQUENTIAL advances on every access, so assert the stable
        // profile characteristics (sequential contexts live in cx.NNN groups).
        val id = AbstractPulsarSession.browserIdFor(BrowserProfileMode.SEQUENTIAL)
        assertTrue(id.profile.isGroup, "expected group profile but was ${id.profile.ident}")
        assertFalse(id.profile.isTemporary, "expected non-temporary profile but was ${id.profile.ident}")
    }

    @Test
    fun `browserIdFor maps TEMPORARY to a temporary profile`() {
        val id = AbstractPulsarSession.browserIdFor(BrowserProfileMode.TEMPORARY)
        assertTrue(id.profile.isTemporary, "expected temporary profile but was ${id.profile.ident}")
        assertFalse(id.profile.isPermanent, "expected non-permanent profile but was ${id.profile.ident}")
    }

    // ------------------------------------------------------------------
    // Capability chain: open --headed / --headless -> sessionConfig
    // browser.display.mode. createBoundDriver keys off this value to
    // decide whether to launch with session-level settings.
    // ------------------------------------------------------------------

    @Test
    fun `headed capability sets GUI display mode on the session config`() {
        val sessionConfig = VolatileConfig(false)
        PulsarSettings.parse(mapOf("headed" to true)).overrideConfiguration(sessionConfig)
        assertEquals("GUI", sessionConfig[BROWSER_DISPLAY_MODE])
    }

    @Test
    fun `headless capability sets HEADLESS display mode on the session config`() {
        val sessionConfig = VolatileConfig(false)
        PulsarSettings.parse(mapOf("headed" to false)).overrideConfiguration(sessionConfig)
        assertEquals("HEADLESS", sessionConfig[BROWSER_DISPLAY_MODE])
    }

    @Test
    fun `explicit displayMode capability takes priority over headed flag`() {
        val sessionConfig = VolatileConfig(false)
        PulsarSettings.parse(mapOf("displayMode" to "GUI", "headed" to false)).overrideConfiguration(sessionConfig)
        assertEquals("GUI", sessionConfig[BROWSER_DISPLAY_MODE])
    }

    @Test
    fun `no display capability leaves the display mode unset`() {
        val sessionConfig = VolatileConfig(false)
        PulsarSettings.parse(emptyMap()).overrideConfiguration(sessionConfig)
        assertNull(sessionConfig[BROWSER_DISPLAY_MODE])
    }
}
