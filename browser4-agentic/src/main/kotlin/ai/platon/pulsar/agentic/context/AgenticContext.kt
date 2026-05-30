package ai.platon.pulsar.agentic.context

import ai.platon.browser4.common.B4Constants
import ai.platon.browser4.protocol.browser.DefaultBrowserManager
import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.browser.BrowserManager
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.ql.SQLSession
import ai.platon.pulsar.ql.SessionConfig
import ai.platon.pulsar.ql.SessionDelegate
import ai.platon.pulsar.ql.context.AbstractH2SQLContext
import ai.platon.pulsar.ql.context.SQLContext
import ai.platon.pulsar.ql.h2.H2SessionDelegate
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.support.ContextDefaults
import ai.platon.pulsar.skeleton.session.BasicPulsarSession
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.StaticApplicationContext

interface AgenticContext : SQLContext {
    override fun createSession(): AgenticSession
    override fun getOrCreateSession(): AgenticSession
    override fun createSession(settings: PulsarSettings): AgenticSession
    override fun getOrCreateSession(settings: PulsarSettings): AgenticSession
    override fun createSession(sessionDelegate: SessionDelegate): SQLSession
}

abstract class AbstractAgenticContext(
    applicationContext: AbstractApplicationContext
) : AbstractH2SQLContext(applicationContext), AgenticContext {
    private val logger = getLogger(this)

    val initConfiguration = MutableConfig(true)

    abstract override fun createSession(): AbstractAgenticSession

    abstract override fun createSession(settings: PulsarSettings): AbstractAgenticSession

    override fun getOrCreateSession(): AgenticSession =
        sessions.values.filterIsInstance<AgenticSession>().firstOrNull() ?: createSession()

    override fun getOrCreateSession(settings: PulsarSettings): AgenticSession {
        // TODO: consider changed settings, for example, REST-level sessionId requires associated PulsarSession
        return sessions.values.filterIsInstance<AgenticSession>().firstOrNull() ?: createSession()
    }

    @Throws(Exception::class)
    override fun createSession(sessionDelegate: SessionDelegate): SQLSession {
        require(sessionDelegate is H2SessionDelegate)
        val session = sqlSessions.computeIfAbsent(sessionDelegate.id) {
            AgenticQLSession(this, sessionDelegate, SessionConfig(sessionDelegate, configuration))
        }
        logger.info("AgenticQLSession is created | #{}/{}/{}", session.id, sessionDelegate.id, id)
        return session as AgenticQLSession
    }
}

open class BasicAgenticContext(
    override val applicationContext: AbstractApplicationContext
) : AbstractAgenticContext(applicationContext) {

    /**
     * Create a [GenericAgenticSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): BasicAgenticSession {
        val session = BasicAgenticSession(this, initConfiguration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): BasicAgenticSession {
        val session = BasicAgenticSession(this, initConfiguration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }
}

open class GenericAgenticContext(
    override val applicationContext: GenericApplicationContext,
    autoRefresh: Boolean = true
) : AbstractAgenticContext(applicationContext) {
    /**
     * Create a [GenericAgenticSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): GenericAgenticSession {
        val session = GenericAgenticSession(this, initConfiguration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): GenericAgenticSession {
        val session = GenericAgenticSession(this, initConfiguration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }

    init {
        if (autoRefresh) {
            applicationContext.refresh()
        }
//        System.err.println("WARNING: Initialized static application context, " +
//                "this context is designed for test purpose only. " +
//                "Use @Browser4AutoConfiguration in spring-boot application for full functionality in production")
    }
}

/**
 * Simple static agentic context, used for test only.
 * */
open class StaticAgenticContext(
    override val applicationContext: StaticApplicationContext = StaticApplicationContext(),
    autoRefresh: Boolean = true
) : GenericAgenticContext(applicationContext, false) {

    private val defaults = ContextDefaults()

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

    override val browserManager: BrowserManager by lazy { DefaultBrowserManager(configuration) }

    /**
     * Create a [StaticAgenticSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): StaticAgenticSession {
        val session = StaticAgenticSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): StaticAgenticSession {
        val session = StaticAgenticSession(this, configuration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }

    init {
        if (autoRefresh) {
            applicationContext.refresh()
        }
//        System.err.println("WARNING: Initialized static application context, " +
//                "this context is designed for test purpose only. " +
//                "Use @Browser4AutoConfiguration in spring-boot application for full functionality in production")
    }
}

open class AnnotationConfigAgenticContext(
    override val applicationContext: AnnotationConfigApplicationContext,
) : AbstractAgenticContext(applicationContext) {

    constructor(vararg componentClasses: Class<*>) : this(AnnotationConfigApplicationContext(*componentClasses))

    /**
     * Create a [BasicPulsarSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): BasicAgenticSession {
        val session = BasicAgenticSession(this, configuration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): BasicAgenticSession {
        val session = BasicAgenticSession(this, configuration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }
}

class DefaultAnnotationConfigAgenticContext(
    vararg componentClasses: Class<*>
) : AnnotationConfigAgenticContext(*componentClasses)

open class ClassPathXmlAgenticContext(applicationContext: ClassPathXmlApplicationContext) :
    AbstractAgenticContext(applicationContext) {

    constructor(configLocation: String) : this(ClassPathXmlApplicationContext(configLocation))

    /**
     * Create a [BasicPulsarSession].
     *
     * > **NOTE:** The session is not a SQLSession, use [execute], [executeQuery] to access [ai.platon.pulsar.ql.SQLSession].
     * */
    @Throws(Exception::class)
    override fun createSession(): BasicAgenticSession {
        val session = BasicAgenticSession(this, initConfiguration.toVolatileConfig())
        return session.also { sessions[it.id] = it }
    }

    override fun createSession(settings: PulsarSettings): BasicAgenticSession {
        val session = BasicAgenticSession(this, initConfiguration.toVolatileConfig())
        settings.label?.let { session.label = it }
        settings.overrideConfiguration(session.sessionConfig)
        return session.also { sessions[it.id] = it }
    }
}

open class DefaultClassPathXmlAgenticContext() : ClassPathXmlAgenticContext(
    System.getProperty(
        CapabilityTypes.APPLICATION_CONTEXT_CONFIG_LOCATION,
        B4Constants.BROWSER4_CONTEXT_CONFIG_LOCATION
    )
)
