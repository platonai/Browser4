package ai.platon.pulsar.protocol.browser.impl

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.concurrent.GracefulScheduledExecutor
import java.time.Duration

open class BrowserMonitor(
    private val browserManager: BasicBrowserManager,
    initialDelay: Long = 30,
    interval: Long = 30
): GracefulScheduledExecutor(Duration.ofSeconds(initialDelay), Duration.ofSeconds(interval)) {
    override fun run() {
        if (!AppContext.isActive) {
            close()
            return
        }

        browserManager.maintain()
    }
}
