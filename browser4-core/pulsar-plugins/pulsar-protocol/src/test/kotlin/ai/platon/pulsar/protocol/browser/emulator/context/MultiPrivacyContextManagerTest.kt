package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserProfile
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.notExists

class MultiPrivacyContextManagerTest {
    private val manager = MultiPrivacyContextManager(DefaultWebDriverPoolManager(ImmutableConfig()))
    private lateinit var agent: BrowserProfile

    @BeforeEach
    fun setUp() {
        agent = BrowserProfile.createRandomTemp()
    }

    @AfterEach
    fun tearDown() {
        FileUtils.deleteDirectory(agent.contextDir.toFile())
        assertTrue(agent.contextDir.notExists())
    }

    @Test
    fun testCreateUnmanagedContext() {
        val context = manager.createUnmanagedContext(agent)
        assertNotNull(context)
        assertTrue(context.isReady)
        assertTrue(context.isActive)
        assertTrue(context.isUnderLoaded)
        assertFalse(context.isFullCapacity)
        assertFalse(context.isRetired)
        assertFalse(context.isClosed)
        assertFalse(context.isHighFailureRate)
        assertFalse(context.isIdle)
        assertFalse(context.isLeaked)
    }
}
