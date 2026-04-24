package ai.platon.pulsar.skeleton.common.message

import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.persist.WebPage

/**
 * Created by Vincent on 16-10-12.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 *
 * Write misc messages into misc sinks
 */
class MiscMessageWriter: MultiSinkMessageWriter() {

    fun debugIllegalLastFetchTime(page: WebPage) {
        val report = String.format(
            "ft: {} lft: {}, fc: {} fh: {} status: {}",
            page.fetchTime,
            page.prevFetchTime,
            page.fetchCount,
            "",
            page.protocolStatus,
        )

        write(report, "illegal-last-fetch-time.txt")
    }

    fun debugLongUrls(report: String) {
        write(report, "urls-long.txt")
    }
}
