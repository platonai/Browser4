package ai.platon.pulsar.skeleton.context

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.skeleton.context.PulsarContexts.shutdown
import ai.platon.pulsar.skeleton.context.support.*
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.StaticApplicationContext

/**
 * Manages the creation and lifecycle of Pulsar contexts and sessions.
 *
 * A Pulsar session provides:
 * - Full-featured `WebDriver`
 * - Capture of live web pages into a local `WebPage`
 * - Parsing a `WebPage` into a lightweight `Document`
 * - Event handlers across the WebPage lifecycle
 * - One-line scrapers & full crawler (fetching, parsing, scheduling, priorities, browser pool, plugins)
 * - Basic LLM support for interacting with pages or documents
 *
 * Additional context types:
 * - `SQLContexts`: enables X‑SQL for advanced web page modeling
 * - `PulsarContexts`: enables agentic/browser‑based agents (`AgenticSession`)
 *
 * This object coordinates the active context, shutdown hooks, and session creation.
 *
 * Thread‑safety:
 * - All creation and shutdown entry points are synchronized.
 */
object PulsarContexts {
    private val logger = getLogger(this)

    private val contexts = mutableSetOf<PulsarContext>()

    /**
     * The active context (the most recently created context).
     */
    var activeContext: PulsarContext? = null
        private set

    /**
     * Creates and activates a new default context if none is active; otherwise returns the existing active context.
     *
     * @return The active context
     */
    @Synchronized
    @JvmStatic
    fun create(): PulsarContext {
        return create(StaticPulsarContext(autoRefresh = true)).also { activeContext = it }
    }

    @Synchronized
    @JvmStatic
    fun getOrCreate(): PulsarContext {
        return getActivatedContextOrNull() ?: create()
    }

    /**
     * Activates the given context unless an equivalent active context already exists; in that case the existing one is returned.
     * Also registers shutdown hooks for both Spring and Pulsar contexts.
     *
     * @param context The context to activate
     * @return The active context
     */
    @Synchronized
    @JvmStatic
    fun create(context: PulsarContext): PulsarContext {
        contexts.add(context)
        activeContext = context

        // NOTE: The order of registered shutdown hooks is not guaranteed.
        (context as? AbstractPulsarContext)?.applicationContext?.registerShutdownHook()
        context.registerShutdownHook()
        val count = contexts.count()
        val message = contexts.joinToString(" | ") { it::class.qualifiedName + " #" + it.id }
        logger.info("Total {} active contexts: {}", count, message)

        return context
    }

    @Synchronized
    @JvmStatic
    fun getOrCreate(context: PulsarContext): PulsarContext {
        val activated = getActivatedContextOrNull()

        // TODO: review the class check, is it a good choice to create at most one object for each context class?
        if (activated != null && activated::class == context::class && activated.isActive) {
            logger.info("Context is already activated | {}", activated::class)
            return activated
        }

        return create(context)
    }

    /**
     * Creates and activates a new context backed by the provided Spring application context if none compatible is active;
     * otherwise returns the existing active context.
     *
     * @param applicationContext The Spring application context
     * @return The active context
     */
    @Synchronized
    fun create(applicationContext: ApplicationContext): PulsarContext {
        return when (applicationContext) {
            is ClassPathXmlApplicationContext -> create(ClassPathXmlPulsarContext(applicationContext))
            is AnnotationConfigApplicationContext -> create(AnnotationConfigPulsarContext(applicationContext))
            is StaticApplicationContext -> create(StaticPulsarContext(applicationContext))
            is GenericApplicationContext -> create(GenericPulsarContext(applicationContext))
            else -> create(BasicPulsarContext(applicationContext as AbstractApplicationContext))
        }
    }

    @Synchronized
    @JvmStatic
    fun getOrCreate(applicationContext: ApplicationContext): PulsarContext {
        val context = activeContext

        if ((context as? AbstractPulsarContext)?.applicationContext == applicationContext) {
            return activeContext as PulsarContext
        }

        return create(applicationContext)
    }

    /**
     * Creates and activates a new context from the given Spring XML location if none compatible is active;
     * otherwise returns the existing active context.
     *
     * @param contextLocation The classpath location of the Spring XML context
     * @return The active context
     */
    @Synchronized
    @JvmStatic
    fun create(contextLocation: String) = create(ClassPathXmlApplicationContext(contextLocation))

    @Synchronized
    @JvmStatic
    fun getOrCreate(contextLocation: String): PulsarContext = getOrCreate(ClassPathXmlApplicationContext(contextLocation))

    /**
     * Creates a `PulsarSession` using the active context (creating a default context if necessary).
     *
     * @return The created session
     */
    @Synchronized
    @JvmStatic
    @Throws(Exception::class)
    fun createSession() = getOrCreate().createSession()

    /**
     * Returns the existing `PulsarSession` if present, otherwise creates one using the active context
     * (creating a default context if necessary).
     *
     * @return The existing or newly created session
     */
    @Synchronized
    @JvmStatic
    @Throws(Exception::class)
    fun getOrCreateSession() = getOrCreate().getOrCreateSession()

    /**
     * Waits for all submitted URLs to be processed.
     */
    @JvmStatic
    @Throws(InterruptedException::class)
    fun await() {
        activeContext?.await()
    }

    /**
     * Registers a closable object with the active context.
     * Note: If a context has not been created yet, this is a no‑op.
     *
     * @param closable The object implementing `AutoCloseable`
     * @param priority The priority for closing order
     * @see AutoCloseable
     * @see PulsarContext.registerClosable
     */
    @JvmStatic
    fun registerClosable(closable: AutoCloseable, priority: Int = 0) {
        activeContext?.registerClosable(closable, priority)
    }

    /**
     * Closes all created contexts and shuts down Browser4.
     */
    @Synchronized
    @JvmStatic
    fun shutdown() {
        contexts.forEach { cx -> cx.runCatching { close() }.onFailure { warnForClose(this, it) } }
        contexts.clear()
        activeContext = null
    }

    /**
     * Closes all created contexts and shuts down Browser4 (alias for [shutdown]).
     */
    @Synchronized
    @JvmStatic
    fun close() = shutdown()

    private fun getActivatedContextOrNull(): PulsarContext? {
        val activated = activeContext
        if (activated != null && activated.isActive) {
            logger.debug("Context is already activated | {}#{}", activated::class, activated.id)
            return activated
        }

        return null
    }
}
