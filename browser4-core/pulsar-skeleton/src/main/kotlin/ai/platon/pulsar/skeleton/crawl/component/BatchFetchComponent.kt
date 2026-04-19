package ai.platon.pulsar.skeleton.crawl.component

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.skeleton.crawl.CoreMetrics
import ai.platon.pulsar.skeleton.crawl.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.crawl.protocol.ProtocolFactory

class BatchFetchComponent(
    val webDb: WebDb,
    val globalCacheFactory: GlobalCacheFactory,
    coreMetrics: CoreMetrics? = null,
    protocolFactory: ProtocolFactory,
    immutableConfig: ImmutableConfig
) : FetchComponent(coreMetrics, protocolFactory, immutableConfig) {
    constructor(webDb: WebDb, immutableConfig: ImmutableConfig) : this(
        webDb, GlobalCacheFactory(immutableConfig), null, ProtocolFactory(immutableConfig), immutableConfig
    )
}
