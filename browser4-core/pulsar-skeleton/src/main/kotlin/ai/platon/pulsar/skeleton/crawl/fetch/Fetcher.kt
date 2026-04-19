package ai.platon.pulsar.skeleton.crawl.fetch

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.protocol.Response

interface WebDriverFetcher {
    @Throws(Exception::class)
    suspend fun fetchDeferred(task: FetchTask, driver: WebDriver): FetchResult
    @Throws(Exception::class)
    suspend fun fetchDeferred(url: String, driver: WebDriver): FetchResult
}

interface Fetcher {
    
    @Throws(Exception::class)
    fun fetch(url: String): Response
    
    @Throws(Exception::class)
    fun fetch(url: String, conf: VolatileConfig): Response
    
    /**
     * Fetch page content.
     *
     * @param page the page to fetch
     * @return the response
     * */
    @Throws(Exception::class)
    fun fetchContent(page: WebPage): Response
    
    /**
     * Fetch a url.
     *
     * @param url the url to fetch
     * @return the response
     * */
    @Throws(Exception::class)
    suspend fun fetchDeferred(url: String): Response
    
    /**
     * Fetch a url.
     *
     * @param url the url to fetch
     * @return the response
     * */
    @Throws(Exception::class)
    suspend fun fetchDeferred(url: String, volatileConfig: VolatileConfig): Response
    
    /**
     * Fetch page content.
     *
     * @param page the page to fetch
     * @return the response
     * */
    @Throws(Exception::class)
    suspend fun fetchContentDeferred(page: WebPage): Response
}