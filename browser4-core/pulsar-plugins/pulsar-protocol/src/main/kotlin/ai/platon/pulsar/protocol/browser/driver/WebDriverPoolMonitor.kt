package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.concurrent.GracefulScheduledExecutor
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.common.warnInterruptible
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The web driver pool monitor.
 * */
open class WebDriverPoolMonitor(
        val driverPoolManager: WebDriverPoolManager,
        val conf: ImmutableConfig,
        initialDelay: Long = 30,
        interval: Long = 30
): GracefulScheduledExecutor(Duration.ofSeconds(initialDelay), Duration.ofSeconds(interval)) {
    private val logger = LoggerFactory.getLogger(WebDriverPoolMonitor::class.java)
    val isActive get() = !isClosed && AppContext.isActive

    override fun run() {
        if (!isActive) {
            close()
            return
        }

        kotlin.runCatching { releaseLocksIfNecessary() }.onFailure { warnInterruptible(this, it) }

        // should maintain in a global monitor
        kotlin.runCatching { driverPoolManager.maintain() }.onFailure { warnInterruptible(this, it) }
    }

    private fun releaseLocksIfNecessary() {
        if (driverPoolManager.isIdle) {
            if (driverPoolManager.hasEvent) {
                logger.info("[Idle] {}", driverPoolManager.toString())
            }

            if (driverPoolManager.isPreempted) {
                // seems never happen
                driverPoolManager.releaseLocks()
            }
        }
    }
}
