package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.CapabilityTypes.PRIVACY_CONTEXT_CLOSE_LAZY
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.AppSystemInfo
import ai.platon.pulsar.skeleton.crawl.fetch.FetchResult
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.WebDriverFetcher
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the lifecycle of privacy contexts, including permanent and temporary contexts.
 * Permanent contexts have a long lifecycle and are never deleted, while temporary contexts
 * are short-lived and discarded if a privacy leak is detected.
 *
 * @param conf The immutable configuration used to initialize the privacy manager.
 */
abstract class AbstractPrivacyManager(
    override val conf: ImmutableConfig
): PrivacyManager {
    private val logger = LoggerFactory.getLogger(AbstractPrivacyManager::class.java)
    private val closed = AtomicBoolean()

    /**
     * Permanent contexts have a long lifecycle and are never deleted by the system.
     * Predefined privacy agents for permanent contexts include:
     * 1. PrivacyAgent.SYSTEM_DEFAULT
     * 2. PrivacyAgent.PROTOTYPE
     * 3. PrivacyAgent.DEFAULT
     * 4. PrivacyAgent.NEXT_SEQUENTIAL
     */
    val permanentContexts = ConcurrentHashMap<BrowserProfile, PrivacyContext>()

    /**
     * Temporary contexts have a short lifecycle and are discarded if a privacy leak is detected.
     * A new context is created to replace the leaked one.
     */
    val temporaryContexts = ConcurrentHashMap<BrowserProfile, PrivacyContext>()

    /**
     * Returns all active contexts, including both permanent and temporary contexts.
     */
    val activeContexts get() = permanentContexts + temporaryContexts

    /**
     * Zombie contexts are contexts that are no longer active but have not yet been fully closed.
     */
    val zombieContexts = ConcurrentLinkedDeque<PrivacyContext>()

    /**
     * Dead contexts are contexts that have been fully closed and their resources released.
     */
    val deadContexts = ConcurrentLinkedDeque<PrivacyContext>()

    /**
     * Monitor object used to synchronize operations on contexts.
     */
    val contextLifeCycleMonitor = Any()

    /**
     * The strategy used to close contexts, either ASAP (as soon as possible) or LAZY.
     */
    private val closeStrategy get() = conf.get(PRIVACY_CONTEXT_CLOSE_LAZY, CloseStrategy.ASAP.name)

    /**
     * Coroutine scope used to schedule context cleaning tasks.
     */
    private val cleaningScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Factory for generating privacy agents.
     */
    protected val privacyAgentGeneratorFactory = PrivacyAgentGeneratorFactory(conf)

    /**
     * The generator used to create privacy agents.
     */
    open val privacyAgentGenerator get() = privacyAgentGeneratorFactory.generator

    /**
     * Indicates whether the privacy manager is closed.
     */
    override val isClosed get() = closed.get()

    /**
     * Indicates whether the privacy manager is active.
     */
    override val isActive get() = !isClosed && AppContext.isActive

    /**
     * Runs a fetch task within a privacy context.
     *
     * @param task The fetch task to execute.
     * @param fetchFun The function to execute the fetch task.
     * @return The result of the fetch task.
     */
    @Throws(Exception::class)
    abstract override suspend fun run(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult

    /**
     * Attempts to get the next ready privacy context for a given page, fingerprint, and task.
     *
     * @param page The web page associated with the context.
     * @param fingerprint The fingerprint used to identify the context.
     * @param task The fetch task associated with the context.
     * @return The next ready privacy context.
     */
    abstract override fun tryGetNextReadyPrivacyContext(page: WebPage, fingerprint: Fingerprint, task: FetchTask): PrivacyContext

    /**
     * Attempts to get the next ready privacy context for a given fingerprint.
     *
     * @param fingerprint The fingerprint used to identify the context.
     * @return The next ready privacy context.
     */
    abstract override fun tryGetNextReadyPrivacyContext(fingerprint: Fingerprint): PrivacyContext

    /**
     * Attempts to get the next under-loaded privacy context for a given page, fingerprint, and task.
     *
     * @param page The web page associated with the context.
     * @param fingerprint The fingerprint used to identify the context.
     * @param task The fetch task associated with the context.
     * @return The next under-loaded privacy context, or null if none is available.
     */
    abstract override fun tryGetNextUnderLoadedPrivacyContext(page: WebPage, fingerprint: Fingerprint, task: FetchTask): PrivacyContext?

    /**
     * Gets or creates a privacy context for the given browser profile.
     *
     * @param profile The browser profile associated with the context.
     * @return The privacy context.
     */
    abstract override fun getOrCreate(profile: BrowserProfile): PrivacyContext

    /**
     * Creates an unmanaged privacy context for the given browser profile.
     *
     * @param profile The browser profile associated with the context.
     * @return The unmanaged privacy context.
     */
    abstract override fun createUnmanagedContext(profile: BrowserProfile): PrivacyContext

    /**
     * Creates an unmanaged privacy context for the given browser profile and fetcher.
     *
     * @param profile The browser profile associated with the context.
     * @param fetcher The web driver fetcher used to create the context.
     * @return The unmanaged privacy context.
     */
    override fun createUnmanagedContext(profile: BrowserProfile, fetcher: WebDriverFetcher) =
        createUnmanagedContext(profile).also { (it as? AbstractPrivacyContext)?.webdriverFetcher = fetcher }

    /**
     * Builds a status string summarizing the current state of active contexts.
     *
     * @return A string representation of the active contexts' status.
     */
    override fun buildStatusString(): String {
        val snapshot = activeContexts.values.joinToString("\n") { it.display + ": " + it.buildStatusString() }
        return snapshot
    }

    /**
     * Performs maintenance tasks on the privacy manager.
     *
     * @param force If true, forces maintenance tasks to run.
     */
    override fun maintain(force: Boolean) {
        // do nothing by default
    }

    /**
     * Closes a given privacy context, moving it from the active list to the zombie list.
     *
     * @param privacyContext The privacy context to close.
     */
    override fun close(privacyContext: PrivacyContext) {
        kotlin.runCatching { doClose(privacyContext) }.onFailure { warnForClose(this, it) }
    }

    /**
     * Resets the privacy environment by closing all privacy contexts.
     *
     * @param reason The reason for resetting the privacy environment.
     */
    override fun reset(reason: String) {
        logger.info("Reset all privacy contexts, closing all ... | {}", reason.ifEmpty { "no reason" })

        activeContexts.values.toCollection(zombieContexts)
        permanentContexts.clear()
        temporaryContexts.clear()
        closeDyingContexts()
    }

    /**
     * Closes the privacy manager, including all active contexts.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing privacy contexts ...")

            activeContexts.values.toCollection(zombieContexts)
            permanentContexts.clear()
            temporaryContexts.clear()
            closeDyingContexts()

            cleaningScope.runCatching { cancel() }.onFailure { warnForClose(this, it) }
        }
    }

    /**
     * Closes a given privacy context, moving it from the active list to the zombie list.
     *
     * @param privacyContext The privacy context to close.
     */
    @Throws(Exception::class)
    private fun doClose(privacyContext: PrivacyContext) {
        if (logger.isDebugEnabled) {
            logger.debug("Closing privacy context | {}", privacyContext.profile)
            logger.debug("Active contexts: {}, zombie contexts: {}", activeContexts.size, zombieContexts.size)
        }

        val privacyAgent = privacyContext.profile

        synchronized(contextLifeCycleMonitor) {
            permanentContexts.remove(privacyAgent)
            temporaryContexts.remove(privacyAgent)

            if (!zombieContexts.contains(privacyContext)) {
                zombieContexts.addFirst(privacyContext)
            }

            val lazyClose = closeStrategy == CloseStrategy.LAZY.name
            when {
                AppSystemInfo.isSystemOverCriticalLoad -> closeDyingContexts()
                lazyClose -> closeZombieContextsLazily()
                else -> closeDyingContexts()
            }
        }
    }

    /**
     * Closes zombie contexts lazily, delaying the resource release.
     */
    private fun closeZombieContextsLazily() {
        cleaningScope.launch {
            delay(5_000)
            closeDyingContexts()
        }
    }

    /**
     * Closes zombie contexts immediately, releasing their resources.
     */
    private fun closeDyingContexts() {
        val dyingContexts = zombieContexts.filter { !it.isClosed }.ifEmpty { return@closeDyingContexts }

        logger.info("Closing {} zombie contexts ...", dyingContexts.size)

        dyingContexts.forEach { privacyContext ->
            privacyContext.runCatching { close() }.onFailure { warnForClose(this, it) }
            zombieContexts.remove(privacyContext)
            deadContexts.add(privacyContext)
        }

        reportHistoricalContexts()
    }

    /**
     * Reports the throughput of the latest temporary contexts.
     */
    private fun reportHistoricalContexts() {
        val maximumRecords = 15
        val historicalContexts = zombieContexts.filter { it.profile.isTemporary } +
            deadContexts.filter { it.profile.isTemporary }
        if (historicalContexts.isNotEmpty()) {
            val prefix = "The latest temporary context throughput: "
            val postfix = " (success/min)"
            historicalContexts.take(maximumRecords)
                .filterIsInstance<AbstractPrivacyContext>()
                .joinToString(", ", prefix, postfix) { String.format("%.2f", 60 * it.meterSuccesses.meanRate) }
                .let { logger.info(it) }
        }
    }
}
