package ai.platon.pulsar.agentic.context

import ai.platon.browser4.driver.common.DisplayMode
import ai.platon.browser4.driver.common.InteractSettings
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContexts.createSession
import ai.platon.pulsar.agentic.context.AgenticContexts.getOrCreateSession
import ai.platon.pulsar.agentic.context.AgenticContexts.shutdown
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import org.springframework.context.ApplicationContext
import org.springframework.context.support.AbstractApplicationContext

/**
 * Coordinates creation and lifecycle of Pulsar agentic contexts and sessions.
 *
 * What an AgenticSession provides:
 * - Agentic/browser-based agents
 * - Full-featured `WebDriver`
 * - Capture of live web pages into a local `WebPage`
 * - Parsing a `WebPage` into a lightweight `Document`
 * - Event handlers across the WebPage lifecycle
 * - One-line scrapers & full crawler (fetching, parsing, scheduling, priorities, crawl pool, plugins)
 * - Basic LLM support for interacting with pages or documents
 *
 * Notes:
 * - This object works with the global [PulsarContexts] to manage the active context and shutdown hooks.
 * - Use [createSession] / [getOrCreateSession] for convenient session bootstrap.
 */
object AgenticContexts {
    /**
     * Create or return the active [AgenticContext].
     * If no active context exists, a default classpath XML based context is created.
     *
     * @return The active or newly created [AgenticContext].
     */
    @Synchronized
    fun create(): AgenticContext = (PulsarContexts.activeContext as? AgenticContext)
        ?: create(DefaultClassPathXmlAgenticContext())

    /**
     * Register and activate the given [context] as the global agentic context.
     *
     * @param context The [AgenticContext] to activate.
     * @return The same [AgenticContext] for call chaining.
     */
    @Synchronized
    fun create(context: AgenticContext): AgenticContext = context.also { PulsarContexts.create(it) }

    /**
     * Create or reuse an [AgenticContext] backed by a Spring [ApplicationContext].
     * If the current active context is a [QLAgenticContext] with the same application context,
     * it will be reused.
     *
     * @param applicationContext The Spring application context.
     * @return The active or newly created [AgenticContext].
     */
    @Synchronized
    fun create(applicationContext: ApplicationContext): AgenticContext {
        val context = PulsarContexts.activeContext
        if (context is QLAgenticContext && context.applicationContext == applicationContext) {
            return PulsarContexts.activeContext as AgenticContext
        }

        return create(QLAgenticContext(applicationContext as AbstractApplicationContext))
    }

    /**
     * Create an [AgenticContext] from a classpath XML [contextLocation].
     *
     * @param contextLocation Classpath location of the Spring XML.
     * @return The newly created [AgenticContext].
     */
    @Synchronized
    fun create(contextLocation: String): AgenticContext = create(ClassPathXmlAgenticContext(contextLocation))

    /**
     * Create a new [AgenticSession] with the provided [settings].
     *
     * @param settings The session creation settings.
     * @return A newly created [AgenticSession].
     */
    @Synchronized
    fun createSession(settings: PulsarSettings): AgenticSession = create().createSession(settings)

    /**
     * Get the existing [AgenticSession] that matches [settings], or create a new one if absent.
     *
     * @param settings The session retrieval/creation settings.
     * @return The existing or newly created [AgenticSession].
     */
    @Synchronized
    fun getOrCreateSession(settings: PulsarSettings): AgenticSession = create().getOrCreateSession(settings)

    /**
     * Convenience factory to create a session with common parameters.
     *
     * @param spa Whether to enable SPA mode. `true` by default; `null` means keep default.
     * @param headless Whether to force headless display mode.
     * @param maxBrowsers Maximum number of browser instances; `null` keeps default.
     * @param maxOpenTabs Maximum number of open tabs; `null` keeps default.
     * @param interactSettings Optional interaction settings for the WebDriver.
     * @return A newly created [AgenticSession].
     */
    @Synchronized
    fun createSession(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null
    ): AgenticSession {
        val displayMode = if (headless) DisplayMode.HEADLESS else null
        val settings = PulsarSettings(spa, displayMode, maxBrowsers, maxOpenTabs, interactSettings, profileMode)
        return createSession(settings)
    }

    /**
     * Convenience accessor to get or create a session with common parameters.
     *
     * @param spa Whether to enable SPA mode. `true` by default; `null` means keep default.
     * @param headless Whether to force headless display mode.
     * @param maxBrowsers Maximum number of browser instances; `null` keeps default.
     * @param maxOpenTabs Maximum number of open tabs; `null` keeps default.
     * @param interactSettings Optional interaction settings for the WebDriver.
     * @return The existing or newly created [AgenticSession].
     */
    @Synchronized
    fun getOrCreateSession(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null,
    ): AgenticSession {
        val displayMode = if (headless) DisplayMode.HEADLESS else null
        val settings = PulsarSettings(spa, displayMode, maxBrowsers, maxOpenTabs, interactSettings, profileMode)
        return getOrCreateSession(settings)
    }

    @Synchronized
    fun createAgent(settings: PulsarSettings): PerceptiveAgent = create().createSession(settings).companionAgent

    @Synchronized
    fun getOrCreateAgent(settings: PulsarSettings): PerceptiveAgent = create().getOrCreateSession(settings).companionAgent

    @Synchronized
    fun createAgent(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null,
    ): PerceptiveAgent = createSession(spa, headless, maxBrowsers, maxOpenTabs, interactSettings, profileMode).companionAgent

    @Synchronized
    fun getOrCreateAgent(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null,
    ): PerceptiveAgent = getOrCreateSession(spa, headless, maxBrowsers, maxOpenTabs, interactSettings, profileMode).companionAgent

    /**
     * A shorthand to get or create the companion agent and run a [task].
     * */
    suspend fun run(task: String) = getOrCreateAgent().run(task)

    /**
     * Block the current thread until the context shutdown is triggered.
     *
     * @throws InterruptedException If the current thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    fun await() = PulsarContexts.await()

    /**
     * Trigger an orderly shutdown of the active context and related resources.
     */
    @Synchronized
    fun shutdown() = PulsarContexts.shutdown()

    /**
     * Close the context (alias of [shutdown]).
     */
    fun close() = shutdown()
}
