package ai.platon.pulsar.skeleton.context.support

import ai.platon.pulsar.browser.manage.BasicBrowserManager
import ai.platon.pulsar.browser.manage.BrowserFactory
import ai.platon.pulsar.chrome.manage.PulsarBrowserFactory
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.loop.TaskLoops
import ai.platon.pulsar.loop.impl.StreamingTaskLoop
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.skeleton.context.PulsarContext
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.component.BatchFetchComponent
import ai.platon.pulsar.skeleton.workflow.component.LoadComponent
import ai.platon.pulsar.skeleton.workflow.component.ParseComponent
import ai.platon.pulsar.skeleton.workflow.component.UpdateComponent
import ai.platon.pulsar.skeleton.workflow.filter.ChainedUrlNormalizer

class TrivialContextDefaults(val context: PulsarContext) {

    /**
     * The default unmodified config
     * */
    val configuration = ImmutableConfig(loadDefaults = true)

    /**
     * Url default normalizer
     * */
    val urlNormalizer = ChainedUrlNormalizer()

    /**
     * The default web db
     * */
    val webDb = WebDb(configuration)

    /**
     * The default global cache
     * */
    val globalCacheFactory = GlobalCacheFactory(configuration)

    /**
     * The default fetch component
     * */
    val fetchComponent = BatchFetchComponent(webDb, configuration)

    /**
     * The default parse component
     * */
    val parseComponent: ParseComponent = ParseComponent(globalCacheFactory, configuration)

    /**
     * The default update component
     * */
    val updateComponent = UpdateComponent(webDb, configuration)

    /**
     * The default load component
     * */
    val loadComponent = LoadComponent(
        webDb, globalCacheFactory, fetchComponent, parseComponent, updateComponent, configuration
    )

    val browserFactory: BrowserFactory = PulsarBrowserFactory(configuration)

    val browserManager = BasicBrowserManager(browserFactory, configuration)

    /**
     * The default main loop
     * */
    val taskLoops = TaskLoops(StreamingTaskLoop(context, configuration))
}
