package ai.platon.pulsar.agentic.context

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_PROFILE_MODE
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
}
