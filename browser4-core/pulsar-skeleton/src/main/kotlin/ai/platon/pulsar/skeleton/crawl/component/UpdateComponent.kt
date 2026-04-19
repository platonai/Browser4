package ai.platon.pulsar.skeleton.crawl.component

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.WebPageExt
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.crawl.schedule.DefaultFetchSchedule
import ai.platon.pulsar.skeleton.crawl.schedule.FetchSchedule
import ai.platon.pulsar.skeleton.crawl.schedule.ModifyInfo
import java.time.Instant

/**
 * The update component.
 */
class UpdateComponent(
    val webDb: WebDb,
    val fetchSchedule: FetchSchedule,
    val messageWriter: MiscMessageWriter? = null,
    val conf: ImmutableConfig,
) : Parameterized {
    constructor(webDb: WebDb, conf: ImmutableConfig) : this(webDb, DefaultFetchSchedule(conf), null, conf)

    fun updateFetchSchedule(page: WebPage) {
        val m = handleModifiedTime(page)
        fetchSchedule.setFetchSchedule(page, m)
    }

    private fun handleModifiedTime(page: WebPage): ModifyInfo {
        val pageExt = WebPageExt(page)

        // page.fetchTime is the time to fetch
        val prevFetchTime = page.fetchTime
        val fetchTime = Instant.now()

        var prevModifiedTime = page.prevModifiedTime
        var modifiedTime = page.modifiedTime
        val newModifiedTime = pageExt.sniffModifiedTime()

        if (newModifiedTime.isAfter(modifiedTime)) {
            prevModifiedTime = modifiedTime
            modifiedTime = newModifiedTime
        }

        return ModifyInfo(fetchTime, prevFetchTime, prevModifiedTime, modifiedTime)
    }
}
