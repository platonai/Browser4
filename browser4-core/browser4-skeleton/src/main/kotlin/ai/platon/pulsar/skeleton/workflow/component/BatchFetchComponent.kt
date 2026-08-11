package ai.platon.pulsar.skeleton.workflow.component

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.skeleton.CoreMetrics
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.protocol.ProtocolFactory

class BatchFetchComponent(
    val webDb: WebDb,
    val globalCacheFactory: GlobalCacheFactory,
    coreMetrics: CoreMetrics? = null,
    protocolFactory: ProtocolFactory,
    immutableConfig: ImmutableConfig
) : FetchComponent(coreMetrics, protocolFactory, immutableConfig) {
    /**
     * Convenience constructor for contexts where no Spring-managed
     * [ProtocolFactory] is available (e.g. unit tests).  The empty factory
     * means every fetch will fail with [ProtocolNotFound] — callers
     * should prefer the primary constructor with a properly wired
     * [ProtocolFactory] whenever possible.
     */
    @Suppress("DEPRECATION")
    @Deprecated(
        message = "Use the primary constructor with a properly wired ProtocolFactory. " +
            "An empty ProtocolFactory causes 'Protocol not found (1600)' for every URL.",
        replaceWith = ReplaceWith(
            "BatchFetchComponent(webDb, globalCacheFactory, null, ProtocolFactory(listOf()), immutableConfig)"
        )
    )
    constructor(webDb: WebDb, immutableConfig: ImmutableConfig) : this(
        webDb, GlobalCacheFactory(immutableConfig), null, ProtocolFactory(listOf()), immutableConfig
    )
}
