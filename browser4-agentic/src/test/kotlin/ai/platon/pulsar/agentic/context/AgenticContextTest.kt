package ai.platon.pulsar.agentic.context

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_CONTEXT_MODE
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class AgenticContextTest {
    private val context = AgenticContexts.create()

    @Test
    fun testCreateSessionWithTemporaryProfile() {
        val settings = PulsarSettings(profileMode = BrowserProfileMode.TEMPORARY)
        val session = context.createSession(settings)
        val profileMode = session.sessionConfig[BROWSER_CONTEXT_MODE]?.toString()?.lowercase()
        assertNotNull(session)
        assertEquals(BrowserProfileMode.TEMPORARY.name.lowercase(), profileMode)

        val driver = session.createBoundDriver()
        val browserId = driver.browser.id
        println(browserId.userDataDir)
        assertTrue(browserId.profile.isTemporary)
        assertTrue(browserId.userDataDir.startsWith(browserId.contextDir))
    }
}
