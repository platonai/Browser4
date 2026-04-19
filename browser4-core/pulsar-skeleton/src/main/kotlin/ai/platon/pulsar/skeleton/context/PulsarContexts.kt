package ai.platon.pulsar.skeleton.context

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.context.support.BasicPulsarContext
import ai.platon.pulsar.skeleton.context.support.ClassPathXmlPulsarContext
import ai.platon.pulsar.skeleton.context.support.StaticPulsarContext
import org.springframework.context.ApplicationContext
import org.springframework.context.support.AbstractApplicationContext

/**
 * Manages the creation and lifecycle of Pulsar contexts and sessions.
 *
 * A Pulsar session provides:
 * - Full-featured `WebDriver`
 * - Capture of live web pages into a local `WebPage`
 * - Parsing a `WebPage` into a lightweight `Document`
 * - Event handlers across the WebPage lifecycle
 * - One-line scrapers & full crawler (fetching, parsing, scheduling, priorities, crawl pool, plugins)
 * - Basic LLM support for interacting with pages or documents
 *
 * Additional context types:
 * - `SQLContexts`: enables X‑SQL for advanced web page modeling
 * - `AgenticContexts`: enables agentic/browser‑based agents (`AgenticSession`)
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
        val activated = activeContext
        if (activated != null && activated.isActive) {
            logger.debug("Context is already activated | {}#{}", activated::class, activated.id)
            return activated
        }

        activeContext = create(StaticPulsarContext())
        return activeContext!!
    }

    @Synchronized
    @JvmStatic
    fun getOrCreate(): PulsarContext = create()

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
        val activated = activeContext
        if (activated != null && activated::class == context::class && activated.isActive) {
            logger.info("Context is already activated | {}", activated::class)
            return activated
        }

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
    fun getOrCreate(context: PulsarContext): PulsarContext = create(context)

    /**
     * Creates and activates a new context from the given Spring XML location if none compatible is active;
     * otherwise returns the existing active context.
     *
     * @param contextLocation The classpath location of the Spring XML context
     * @return The active context
     */
    @Synchronized
    @JvmStatic
    fun create(contextLocation: String) = create(ClassPathXmlPulsarContext(contextLocation))

    @Synchronized
    @JvmStatic
    fun getOrCreate(contextLocation: String): PulsarContext = create(contextLocation)

    /**
     * Creates and activates a new context backed by the provided Spring application context if none compatible is active;
     * otherwise returns the existing active context.
     *
     * @param applicationContext The Spring application context
     * @return The active context
     */
    @Synchronized
    @JvmStatic
    fun create(applicationContext: ApplicationContext) =
        create(BasicPulsarContext(applicationContext as AbstractApplicationContext))

    @Synchronized
    @JvmStatic
    fun getOrCreate(applicationContext: ApplicationContext) = create(applicationContext)

    /**
     * Creates a `PulsarSession` using the active context (creating a default context if necessary).
     *
     * @return The created session
     */
    @Synchronized
    @JvmStatic
    @Throws(Exception::class)
    fun createSession() = create().createSession()

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
}
