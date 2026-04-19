package ai.platon.pulsar.skeleton.crawl.schedule

import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.persist.WebPage
import java.time.Duration
import java.time.Instant

data class ModifyInfo(
    /**
     * The actual latest fetch time, WebPage.fetchTime will be updated by this value
     * */
    var fetchTime: Instant,
    /**
     * The previous actual latest fetch time
     * */
    var prevFetchTime: Instant,
    var prevModifiedTime: Instant,
    var modifiedTime: Instant,
)

interface FetchSchedule : Parameterized {

    val maxFetchInterval: Duration

    fun initializeSchedule(page: WebPage)

    fun setFetchSchedule(page: WebPage, m: ModifyInfo)

    fun setPageGoneSchedule(
        page: WebPage,
        prevFetchTime: Instant, prevModifiedTime: Instant, fetchTime: Instant,
    )

    fun setPageRetrySchedule(
        page: WebPage,
        prevFetchTime: Instant, prevModifiedTime: Instant, fetchTime: Instant,
    )

    fun estimatePrevFetchTime(page: WebPage): Instant

    fun shouldFetch(page: WebPage, now: Instant): Boolean

    fun forceRefetch(page: WebPage, prevFetchTime: Instant, asap: Boolean)
}
