
package ai.platon.pulsar.skeleton.crawl.protocol

import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.metadata.MultiMetadata

class ProtocolOutput(
    val pageDatum: PageDatum?,
    val headers: MultiMetadata,
    val protocolStatus: ProtocolStatus
) {
    constructor(pageDatum: PageDatum) : this(pageDatum, pageDatum.headers, pageDatum.protocolStatus)
    constructor(status: ProtocolStatus) : this(null, MultiMetadata(), status)

    val length get() = pageDatum?.contentLength ?: 0
}
