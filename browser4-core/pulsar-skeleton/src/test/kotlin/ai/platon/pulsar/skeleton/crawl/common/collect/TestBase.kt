package ai.platon.pulsar.skeleton.crawl.common.collect

import ai.platon.pulsar.common.collect.TemporaryLocalFileUrlLoader
import ai.platon.pulsar.common.collect.UrlTopic
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.skeleton.crawl.common.GlobalCache
import kotlin.test.BeforeTest

open class TestBase {
    protected val conf = ImmutableConfig()
    protected val group = UrlTopic("", 0, 0, 10000)
    protected val queueSize = 100
    protected lateinit var urlLoader: TemporaryLocalFileUrlLoader

    protected val globalCache = GlobalCache(conf)
    protected val urlPool get() = globalCache.urlPool

    @BeforeTest
    fun setUp() {
        urlLoader = TemporaryLocalFileUrlLoader()
        val hyperlinks = IntRange(1, queueSize).map { AppConstants.EXAMPLE_URL + "/$it" }
                .mapIndexed { i, url -> Hyperlink(url, "", order = 1 + i) }
        urlLoader.saveAll(hyperlinks, group)
    }
}
