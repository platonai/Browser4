package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem

class PrivacyContextMetrics {
    private val registry get() = MetricsSystem.reg
    val tasks = registry.multiMetric(this, "tasks")
    val successes = registry.multiMetric(this, "successes")
    val finishes = registry.multiMetric(this, "finishes")
    val contextLeaks = registry.multiMetric(this, "contextLeaks")

    val smallPages = registry.meter(this, "smallPages")
    val minorLeakWarnings = registry.meter(this, "minorLeakWarnings")
    val contexts = registry.meter(this, "contexts")
    val leakWarnings = registry.meter(this, "leakWarnings")
}