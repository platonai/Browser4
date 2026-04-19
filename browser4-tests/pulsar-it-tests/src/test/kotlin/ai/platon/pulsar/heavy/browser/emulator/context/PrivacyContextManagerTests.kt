package ai.platon.pulsar.heavy.browser.emulator.context

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.browser4.driver.common.UserAgent
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.DefaultWebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.skeleton.PulsarSettings
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.*

class PrivacyContextManagerTests {
    private val logger = getLogger(this)
    private val contextPathBase = Files.createTempDirectory("test-")
    private val contextPath = contextPathBase.resolve("cx.5kDMDS2")
    private val contextPath2 = contextPathBase.resolve("cx.7KmtAC2")
    private val conf = ImmutableConfig()
    private lateinit var driverPoolManager: WebDriverPoolManager

    @BeforeTest
    fun setup() {
        PulsarSettings.maxBrowserContexts(6).maxOpenTabs(10)
        BrowserSettings.withSequentialBrowsers(15)
        driverPoolManager = DefaultWebDriverPoolManager(conf)
    }

    @AfterTest
    fun tearDown() {
        driverPoolManager.close()
        Files.walk(contextPathBase)
            .filter { it.fileName.toString().startsWith("cx.") }
            .forEach { Files.delete(it) }
    }

    @Test
    fun testPrivacyContextClosing() {
        val privacyManager = MultiPrivacyContextManager(driverPoolManager, conf)
        val userAgents = UserAgent()

        repeat(100) {
            val proxyServer = "127.0.0." + Random.nextInt(200)
            val userAgent = userAgents.getRandomUserAgent()
            val fingerprint = Fingerprint(BrowserType.DEFAULT, proxyServer, userAgent = userAgent)
            val pc = privacyManager.tryGetNextReadyPrivacyContext(fingerprint)

            assertTrue { pc.isActive }
            privacyManager.close(pc)
            assertTrue { !pc.isActive }
            assertFalse { privacyManager.temporaryContexts.containsKey(pc.profile) }
            assertFalse { privacyManager.temporaryContexts.containsValue(pc) }
        }
    }
}
