package ai.platon.pulsar.protocol.browser.context

import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.api.BrowserProfile
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.notExists

class MultiPrivacyContextManagerTest {
    private val manager = MultiPrivacyContextManager(DefaultWebDriverPoolManager(ImmutableConfig()))
    private lateinit var profile: BrowserProfile

    @BeforeEach
    fun setUp() {
        profile = BrowserProfile.createRandomTemp()
    }

    @AfterEach
    fun tearDown() {
        FileUtils.deleteDirectory(profile.contextDir.toFile())
        assertTrue(profile.contextDir.notExists())
    }

    @Test
    fun testCreateUnmanagedContext() {
        val context = manager.createUnmanagedContext(profile)
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
