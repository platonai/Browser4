package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.concurrent.GracefulScheduledExecutor
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnInterruptible
import java.time.Duration

open class PrivacyContextMonitor(
    private val privacyManager: AbstractPrivacyManager,
    initialDelay: Long = 30,
    interval: Long = 30
): GracefulScheduledExecutor(Duration.ofSeconds(initialDelay), Duration.ofSeconds(interval)) {
    private val logger = getLogger(this)

    override fun run() {
        kotlin.runCatching { privacyManager.maintain() }.onFailure { warnInterruptible(this, it) }
    }
}
