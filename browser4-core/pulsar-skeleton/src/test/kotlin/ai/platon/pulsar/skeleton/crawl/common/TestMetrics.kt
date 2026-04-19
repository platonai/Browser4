package ai.platon.pulsar.skeleton.crawl.common

import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.common.sleepSeconds
import kotlin.test.*
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestMetrics {

    init {
        System.setProperty("metrics.report.initial.delay", Duration.ZERO.toString())
        System.setProperty("metrics.graphite.report.interval", Duration.ofSeconds(10).toString())
        System.setProperty("graphite.pickled.batch.size", "10")
        System.setProperty("graphite.server", "crawl2")
        System.setProperty(CapabilityTypes.METRICS_ENABLED, "true")
    }

    private val logger = LoggerFactory.getLogger(TestMetrics::class.java)
    private val conf = MutableConfig()
    private val metrics = MetricsSystem(conf)

    @BeforeTest
    fun setup() {
        metrics.start()
    }

    @AfterTest
    fun tearDown() {
        metrics.close()
    }

    @Test
    fun testProperties() {
        if (!NetUtil.testHttpNetwork(metrics.graphiteServer, 2004)) {
            logger.warn("Graphite server is not available")
            return
        }

        assertEquals(10, metrics.batchSize)
        assertEquals(Duration.ZERO, metrics.initialDelay)
        assertEquals(Duration.ofSeconds(10), metrics.graphiteReportInterval)
        assertEquals("crawl2", metrics.graphiteServer)
        assertTrue { NetUtil.testHttpNetwork(metrics.graphiteServer, 2004) }
    }

    @Test
    fun testGraphiteReporter() {
        if (!NetUtil.testHttpNetwork(metrics.graphiteServer, 2004)) {
            logger.warn("Graphite server is not available")
            return
        }

        val counters = IntRange(1, 10).map { MetricsSystem.reg.counter("test.c$it") }
        var i = 0
        while (i++ < 60) {
            counters.forEachIndexed { j, c -> c.inc(j.toLong()) }
            sleepSeconds(1)
        }
    }
}
