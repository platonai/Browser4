package ai.platon.pulsar.heavy.browser

import ai.platon.pulsar.common.config.CapabilityTypes.MIN_SEQUENTIAL_PRIVACY_AGENT_NUMBER
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.PulsarBrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PulsarBrowserFactoryTest {

    private lateinit var browserFactory: PulsarBrowserFactory
    private val browsers = mutableListOf<Browser>()

    @BeforeEach
    fun setUp() {
        browserFactory = PulsarBrowserFactory()
    }

    @AfterEach
    fun tearDown() {
        browsers.forEach { it.close() }
    }

    @Test
    fun testConnect() {

    }

    @Test
    fun testLaunchNextSequentialBrowserManyTimes() {
        val conf = ImmutableConfig()
        val maxAgents = conf.getInt(MIN_SEQUENTIAL_PRIVACY_AGENT_NUMBER, 10)

        for (i in 1..(maxAgents + 2)) {
            val browser = browserFactory.launchNextSequentialBrowser()
            browsers.add(browser)
            browser.close()
        }

        val contextDirs = browsers.map { it.id.contextDir }.toSet()
        assertTrue("Context dirs should used cyclically\n${contextDirs.joinToString("\n")}") { contextDirs.size <= maxAgents }
    }
}
