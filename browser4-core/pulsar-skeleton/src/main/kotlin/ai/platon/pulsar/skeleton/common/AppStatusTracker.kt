package ai.platon.pulsar.skeleton.common

import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.crawl.CoreMetrics

class AppStatusTracker(
    val metrics: MetricsSystem,
    val coreMetrics: CoreMetrics,
    val messageWriter: MiscMessageWriter
)
