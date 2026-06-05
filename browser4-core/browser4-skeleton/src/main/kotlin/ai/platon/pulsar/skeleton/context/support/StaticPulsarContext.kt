package ai.platon.pulsar.skeleton.context.support

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import org.springframework.context.support.StaticApplicationContext

open class StaticPulsarContext(
    override val applicationContext: StaticApplicationContext = StaticApplicationContext(),
    autoRefresh: Boolean = false,
) : GenericPulsarContext(applicationContext, false) {

    private val logger = getLogger(StaticPulsarContext::class)
    private val throttlingLogger = ThrottlingLogger(logger)

    private val defaults by lazy { TrivialContextDefaults(this) }

    /**
     * The unmodified config
     * */
    override val configuration get() = defaults.configuration

    /**
     * Url normalizer
     * */
    override val urlNormalizer get() = defaults.urlNormalizer

    /**
     * The web db
     * */
    override val webDb get() = defaults.webDb

    /**
     * The global cache
     * */
    override val globalCacheFactory get() = defaults.globalCacheFactory

    /**
     * The fetch component
     * */
    override val fetchComponent get() = defaults.fetchComponent

    /**
     * The parse component
     * */
    override val parseComponent get() = defaults.parseComponent

    /**
     * The update component
     * */
    override val updateComponent get() = defaults.updateComponent

    /**
     * The load component
     * */
    override val loadComponent get() = defaults.loadComponent

    override val globalCache: GlobalCache get() = globalCacheFactory.globalCache

    /**
     * The main loop
     * */
    override val taskLoops get() = defaults.taskLoops

    init {
        if (autoRefresh) {
            applicationContext.refresh()
        }

        throttlingLogger.warn("WARMING: This context is used for TEST only")
    }
}
