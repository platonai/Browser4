package ai.platon.pulsar.skeleton.crawl.schedule

import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.WebPageExt
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.common.persist.ext.options
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

abstract class AbstractFetchSchedule(
    val conf: ImmutableConfig,
    val messageWriter: MiscMessageWriter? = null,
) : FetchSchedule {
    protected var defaultInterval = conf.getDuration(CapabilityTypes.FETCH_DEFAULT_INTERVAL, Duration.ofDays(30))
    override val maxFetchInterval: Duration =
        conf.getDuration(CapabilityTypes.FETCH_MAX_INTERVAL, ChronoUnit.DECADES.duration)

    override fun initializeSchedule(page: WebPage) {
        page.fetchInterval = defaultInterval
        page.fetchRetries = 0
    }

    override fun setFetchSchedule(page: WebPage, m: ModifyInfo) {
        if (page.protocolStatus.isSuccess) {
            page.fetchRetries = 0
        }

//        page.fetchInterval = page.options.fetchInterval
        page.fetchInterval = page.options.expires

        // note: page.fetchTime might not be the same as the actual fetch time
        val now = Instant.now()
        val pageEx = WebPageExt(page)
        pageEx.updateFetchTime(now, now + page.fetchInterval)

        page.modifiedTime = m.modifiedTime
        page.prevModifiedTime = m.prevModifiedTime
    }

    /**
     * This method adjusts the fetch schedule if fetching needs to be re-tried due
     * to transient errors. The default implementation sets the next fetch time 1
     * day in the future and increases the retry counter.
     *
     * @param page             WebPage to retry
     * @param prevFetchTime    previous fetch time
     * @param prevModifiedTime previous modified time
     * @param fetchTime        current fetch time
     */
    override fun setPageRetrySchedule(
        page: WebPage,
        prevFetchTime: Instant,
        prevModifiedTime: Instant,
        fetchTime: Instant,
    ) {
        page.fetchRetries++
        // retry immediately, this is the default behaviour
        val now = Instant.now()
        page.fetchInterval = Duration.ofSeconds(0)
        val pageEx = WebPageExt(page)
        pageEx.updateFetchTime(now, now)
    }

    /**
     * This method specifies how to schedule refetching of pages marked as GONE.
     * Default implementation increases fetchInterval by 50% but the value may
     * never exceed `maxInterval`.
     *
     * @param page
     * @return adjusted page information, including all original information.
     * NOTE: this may be a different instance than
     */
    override fun setPageGoneSchedule(
        page: WebPage, prevFetchTime: Instant, prevModifiedTime: Instant, fetchTime: Instant,
    ) {
        page.fetchInterval = ChronoUnit.DECADES.duration
        val now = Instant.now()
        val pageEx = WebPageExt(page)
        pageEx.updateFetchTime(now, now + page.fetchInterval)
    }

    /**
     * This method return the last fetch time of the WebPage
     *
     * @return the date as a long.
     */
    override fun estimatePrevFetchTime(page: WebPage): Instant {
        return page.fetchTime - page.fetchInterval
    }

    /**
     * This method provides information whether the page is suitable for selection
     * in the current fetchlist. NOTE: a true return value does not guarantee that
     * the page will be fetched, it just allows it to be included in the further
     * selection process based on scores. The default implementation checks
     * `fetchTime`, if it is higher than the
     *
     * @param page    Web page to fetch
     * @param now reference time (usually set to the time when the fetchlist
     * generation process was started).
     * @return true, if the page should be considered for inclusion in the current
     * fetchlist, otherwise false.
     */
    override fun shouldFetch(page: WebPage, now: Instant): Boolean {
        if (page.options.isExpired(now)) {
            return true
        }

        // Pages are never truly GONE - we have to check them from time to time.
        // pages with too long fetchInterval are adjusted so that they fit within
        // maximum fetchInterval (batch retention period).
        val fetchTime = page.fetchTime

        return fetchTime < now
    }

    /**
     * This method resets fetchTime, fetchInterval, modifiedTime,
     * retriesSinceFetch and page text, so that it forces refetching.
     *
     * @param page
     * @param asap if true, force refetch as soon as possible - this sets the
     * fetchTime to now. If false, force refetch whenever the next fetch
     * time is set.
     */
    override fun forceRefetch(page: WebPage, prevFetchTime: Instant, asap: Boolean) {
        val pageEx = WebPageExt(page)
        // reduce fetchInterval so that it fits within the max value
        if (page.fetchInterval > maxFetchInterval) {
            pageEx.setFetchInterval(maxFetchInterval.seconds * 0.9f)
            // page.fetchInterval = Duration.ofSeconds((maxFetchInterval.seconds * 0.9f).toLong())
        }
        page.fetchRetries = 0

        val fetchInterval = if (asap) Duration.ZERO else page.fetchInterval
        val now = Instant.now()
        pageEx.updateFetchTime(now, now + fetchInterval)
    }
}
