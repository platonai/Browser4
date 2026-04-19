package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.persist.WebPage
import java.time.Duration

interface TaskRunner : AutoCloseable {
    /**
     * The crawl id
     * */
    val id: Int

    /**
     * The crawl name
     * */
    val name: String

    /**
     * Delay policy for retry tasks
     * */
    var retryDelayPolicy: (Int, UrlAware?) -> Duration

    fun pause()

    fun resume()

    /**
     * Wait until all tasks are done.
     * */
    @Throws(InterruptedException::class)
    fun await()

    fun report()

    fun onWillLoad(url: UrlAware)

    fun onLoad(url: UrlAware)

    fun onLoaded(url: UrlAware, page: WebPage?)
}
