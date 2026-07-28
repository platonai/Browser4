package ai.platon.browser4.common

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
}
