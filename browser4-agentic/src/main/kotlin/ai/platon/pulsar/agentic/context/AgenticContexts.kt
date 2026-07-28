package ai.platon.pulsar.agentic.context

import ai.platon.browser4.common.B4Constants.SWARM_SESSION_LABEL
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.context.AgenticContexts.createSession
import ai.platon.pulsar.agentic.context.AgenticContexts.getOrCreateSession
import ai.platon.pulsar.agentic.context.AgenticContexts.shutdown
import ai.platon.browser4.api.InteractSettings
import ai.platon.browser4.api.model.DisplayMode
import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.StaticApplicationContext

/**
 * Coordinates creation and lifecycle of agentic contexts and sessions.
 *
 * What an AgenticSession provides:
 * - Agentic/browser-based agents
 * - Full-featured `WebDriver`
 * - Capture of live web pages into a local `WebPage`
 * - Parsing a `WebPage` into a lightweight `Document`
 * - Event handlers across the WebPage lifecycle
 * - One-line scrapers & full crawler (fetching, parsing, scheduling, priorities, browser pool, plugins)
 * - Basic LLM support for interacting with pages or documents
 *
 * Notes:
 * - This object works with the global [PulsarContexts] to manage the active context and shutdown hooks.
 * - Use [createSession] / [getOrCreateSession] for convenient session bootstrap.
 */
@Suppress("unused")
object AgenticContexts {
    init {
        Systems.setPropertyIfAbsent(CapabilityTypes.APP_NAME_KEY, "browser4")
    }

    /**
     * Create or return the active [AgenticContext].
     * If no active context exists, a default classpath XML based context is created.
     *
     * @return The active or newly created [AgenticContext].
     */
    @Synchronized
    fun create(): AgenticContext {
        return create(StaticAgenticContext(autoRefresh = true))
    }

    @Synchronized
    fun getOrCreate(): AgenticContext {
        return getActivatedContextOrNull() ?: create()
    }

    /**
     * Register and activate the given [context] as the global agentic context.
     *
     * @param context The [AgenticContext] to activate.
     * @return The same [AgenticContext] for call chaining.
     */
    @Synchronized
    fun create(context: AgenticContext): AgenticContext {
        return PulsarContexts.create(context) as AgenticContext
    }

    @Synchronized
    fun getOrCreate(context: AgenticContext): AgenticContext {
        return getActivatedContextOrNull() ?: create(context)
    }

    /**
     * Create or reuse an [AgenticContext] backed by a Spring [ApplicationContext].
     * If the current active context is a [GenericAgenticContext] with the same application context,
     * it will be reused.
     *
     * @param applicationContext The Spring application context.
     * @return The active or newly created [AgenticContext].
     */
    @Synchronized
    fun create(applicationContext: ApplicationContext): AgenticContext {
        return when (applicationContext) {
            is ClassPathXmlApplicationContext -> create(ClassPathXmlAgenticContext(applicationContext))
            is AnnotationConfigApplicationContext -> create(AnnotationConfigAgenticContext(applicationContext))
            is StaticApplicationContext -> create(StaticAgenticContext(applicationContext))
            is GenericApplicationContext -> create(GenericAgenticContext(applicationContext))
            else -> create(BasicAgenticContext(applicationContext as AbstractApplicationContext))
        }
    }

    @Synchronized
    fun getOrCreate(applicationContext: ApplicationContext): AgenticContext {
        val context = getActivatedContextOrNull()

        if ((context as? AbstractAgenticContext)?.applicationContext == applicationContext) {
            return context
        }

        return create(applicationContext)
    }

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

    /**
     * Create a pulsar session
     * */
    @Synchronized
    @JvmStatic
    @Throws(Exception::class)
    fun ensureSwarmSession(settings: PulsarSettings, applicationContext: ApplicationContext): AgenticSession {
        val context = getOrCreate(applicationContext) as AbstractAgenticContext
        val swarmSession =
            context.sessions.values.filterIsInstance<AgenticSession>().firstOrNull { it.label == SWARM_SESSION_LABEL }
        if (swarmSession != null) {
            return swarmSession
        }

        val lastProfileMode = settings.profileMode
        val profileMode = when (lastProfileMode) {
            BrowserProfileMode.SEQUENTIAL -> BrowserProfileMode.SEQUENTIAL
            BrowserProfileMode.TEMPORARY -> BrowserProfileMode.TEMPORARY
            else -> BrowserProfileMode.SEQUENTIAL
        }
        val settings = settings.copy(label = SWARM_SESSION_LABEL, profileMode = profileMode)
        return context.createSession(settings)
    }

    @Synchronized
    fun createAgent(settings: PulsarSettings): PerceptiveAgent = create().createSession(settings).companionAgent

    @Synchronized
    fun getOrCreateAgent(settings: PulsarSettings): PerceptiveAgent =
        create().getOrCreateSession(settings).companionAgent

    @Synchronized
    fun createAgent(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null,
    ): PerceptiveAgent =
        createSession(spa, headless, maxBrowsers, maxOpenTabs, interactSettings, profileMode).companionAgent

    @Synchronized
    fun getOrCreateAgent(
        spa: Boolean? = true,
        headless: Boolean = false,
        maxBrowsers: Int? = null,
        maxOpenTabs: Int? = null,
        interactSettings: InteractSettings? = null,
        profileMode: BrowserProfileMode? = null,
    ): PerceptiveAgent =
        getOrCreateSession(spa, headless, maxBrowsers, maxOpenTabs, interactSettings, profileMode).companionAgent

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

    private fun getActivatedContextOrNull(): AgenticContext? {
        val activated = PulsarContexts.activeContext
        if (activated is AgenticContext && activated.isActive) {
            return activated
        }

        return null
    }
}
