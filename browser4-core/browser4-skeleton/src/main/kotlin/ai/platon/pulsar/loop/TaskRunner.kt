package ai.platon.pulsar.loop

import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.core.api.WebPage
import java.time.Duration

interface TaskRunner : AutoCloseable {
    /**
     * The browser id
     * */
    val id: Int

    /**
     * The browser name
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
