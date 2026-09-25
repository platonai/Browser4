package ai.platon.pulsar.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Lightweight sanity checks for [B4Constants]. Constants carry configuration keys and fixed
 * identifiers used across the engine, so we assert their expected literal values once to catch
 * accidental renames or typos.
 */
class B4ConstantsTest {

    @Test
    @DisplayName("session capability and profile keys have the expected literal values")
    fun capabilityKeysHaveExpectedValues() {
        assertEquals("sessionId", B4Constants.SESSION_ID_CAPABILITY)
        assertEquals("profileMode", B4Constants.PROFILE_MODE_CAPABILITY)
        assertEquals("browser.profile.mode", B4Constants.BROWSER_PROFILE_MODE)
    }

    @Test
    @DisplayName("well-known session identifiers are non-blank and distinct")
    fun sessionIdentifiersAreNonBlankAndDistinct() {
        assertFalse(B4Constants.DEFAULT_SESSION_ID.isBlank())
        assertFalse(B4Constants.SWARM_SESSION_ID.isBlank())
        assertFalse(B4Constants.SWARM_SESSION_LABEL.isBlank())
        assertEquals(B4Constants.SWARM_SESSION_ID, B4Constants.SWARM_SESSION_LABEL)
    }

    @Test
    @DisplayName("context config location points at the expected classpath resource")
    fun contextConfigLocationIsExpected() {
        assertEquals("classpath:browser4-beans/app-context.xml", B4Constants.BROWSER4_CONTEXT_CONFIG_LOCATION)
    }

    @Test
    @DisplayName("the console capture key and its default are pinned")
    fun consoleCaptureKeyAndDefaultArePinned() {
        assertEquals("browser.console.capture", B4Constants.CONSOLE_CAPTURE_CDP)
        // The CDP capture stays the default: flipping it silently would change whether the
        // `console` command touches the page world, which is what the key exists to decide.
        assertEquals(true, B4Constants.CONSOLE_CAPTURE_CDP_DEFAULT)
    }

    @Test
    @DisplayName("the focus emulation key and its default are pinned")
    fun focusEmulationKeyAndDefaultArePinned() {
        assertEquals("browser.focus.emulation", B4Constants.FOCUS_EMULATION)
        // The emulation stays the default: without it every driven tab advertises itself as an
        // unfocused background tab, which is the signal the key exists to suppress.
        assertEquals(true, B4Constants.FOCUS_EMULATION_DEFAULT)
    }
}
