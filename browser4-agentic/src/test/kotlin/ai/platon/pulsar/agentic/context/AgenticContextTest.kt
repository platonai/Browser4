package ai.platon.pulsar.agentic.context

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_PROFILE_MODE
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import kotlin.test.Test

class AgenticContextTest {
    private val context = AgenticContexts.create()

    @Test
    fun testCreateSessionWithTemporaryProfile() {
        val settings = PulsarSettings(profileMode = BrowserProfileMode.TEMPORARY)
        val session = context.createSession(settings)
        val profileMode = session.sessionConfig[BROWSER_PROFILE_MODE]?.lowercase()
        assertNotNull(session)
        assertEquals(BrowserProfileMode.TEMPORARY.name.lowercase(), profileMode)
    }

    @Test
    fun testGetOrCreateSessionAppliesSettingsWhenCreatingNewSession() {
        val freshContext = AgenticContexts.create() as AbstractAgenticContext
        val settings = PulsarSettings(profileMode = BrowserProfileMode.TEMPORARY)
        val session = freshContext.getOrCreateSession(settings)
        val profileMode = session.sessionConfig[BROWSER_PROFILE_MODE]?.lowercase()
        assertEquals(BrowserProfileMode.TEMPORARY.name.lowercase(), profileMode)
    }

    @Test
    fun testGetOrCreateSessionReusesExistingSessionWithoutCreatingAnother() {
        val freshContext = AgenticContexts.create() as AbstractAgenticContext
        val first = freshContext.getOrCreateSession(PulsarSettings(profileMode = BrowserProfileMode.TEMPORARY))
        val second = freshContext.getOrCreateSession(PulsarSettings(profileMode = BrowserProfileMode.DEFAULT))
        // The existing session is returned as-is — no second PulsarSession is created,
        // and the settings of the second call are intentionally ignored on reuse.
        assertSame(first, second)
        assertEquals(1, freshContext.sessions.size)
    }
}
