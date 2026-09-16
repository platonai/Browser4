package ai.platon.pulsar.loop.impl

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_LABEL
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.collect.UrlFeeder
import ai.platon.pulsar.common.config.CapabilityTypes.CRAWL_ENABLE_DEFAULT_DATA_COLLECTORS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.core.api.PulsarContext
import ai.platon.pulsar.loop.TaskRunner
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch

open class StreamingTaskLoop(
    val context: PulsarContext,
    /**
     * The unmodified configuration load from file
     * */
    configuration: ImmutableConfig,
    /**
     * The loop name
     * */
    name: String = "StreamingTaskLoop"

) : AbstractTaskLoop(name, configuration) {
    private val logger = LoggerFactory.getLogger(StreamingTaskLoop::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("sc"))
    private var crawlJob: Job? = null
    private val started = CountDownLatch(1)

    private lateinit var _taskRunner: StreamingTaskRunner
    override val taskRunner: TaskRunner get() = _taskRunner

    private val urlFeeders = ConcurrentSkipListMap<String, UrlFeeder>()

    /**
     * A UrlFeeder is a wrapper to globalCache.urlPool
     * */
    override val urlFeeder: UrlFeeder get() = getOrCreateUrlFeeder()

    init {
        logger.info("Main loop is created | #{} | {}@{}", id, name, hashCode())
    }

    override val abstract: String
        get() {
            return if (!isRunning) {
                "[stopped] crawler: ${taskRunner.name}#${taskRunner.id}, urlPool: ${urlFeeder.urlPool} \n${urlFeeder.abstract}"
            } else {
                urlFeeder.abstract
            }
        }

    override val report: String
        get() {
            return if (!isRunning) {
                "[stopped] crawler: ${taskRunner.name}#${taskRunner.id}, urlPool: ${urlFeeder.urlPool} \n${urlFeeder.report}"
            } else {
                urlFeeder.report
            }
        }

    @Synchronized
    override fun start() {
        if (isRunning) {
            // issue a warning for debug
            logger.warn("Main loop {} is already running", display)
        }

        if (running.compareAndSet(false, true)) {
            start0()
            val count = urlFeeder.collectors.size
            logger.info("Main loop is started with {} link collectors | #{} | {}@{}", count, id, name, this)
        }
    }

    @Synchronized
    override fun stop() {
        if (running.compareAndSet(true, false)) {
            _taskRunner.close()

            // url feeder should be shared by all crawlers, so we should not clear it
            // _urlFeeder.clear()

            runCatching { runBlocking { crawlJob?.cancelAndJoin() } }
                .onFailure {
                    if (AppContext.isActive) {
                        warnForClose(it, it, "Main loop #${id} is stopped with exception")
                    } else {
                        // it's expected that there are some uncaught exceptions if the system is shutting down,
                        // ignore them.
                        if (logger.isDebugEnabled) {
                            logger.debug("Main loop #${id} is stopped with exception", it)
                        }
                    }
                }

            crawlJob = null
            logger.info("Main loop is stopped | #{} | {}", id, this)
        }
    }

    /**
     * Wait until the loop is started and all tasks are done.
     * */
    @Throws(InterruptedException::class)
    override fun await() {
        started.await()
        taskRunner.await()
    }

    private fun start0() {
        val cx = context
        require(cx is AbstractPulsarContext) { "Expect context is AbstractPulsarContext, actual ${cx::class}#${cx.id}" }
        val applicationContext = cx.applicationContext
        require(applicationContext.isActive) { "Expect context is active | ${applicationContext.id}" }
        require(cx.isActive) { "Expect context is active | ${cx.id}" }

        // The loop consumes the URLs submitted by every session (crawl, swarm,
        // agent, user scripts), so the session it drives must own no browser tab —
        // see [resolveFetchSession].
        val urls = urlFeeder.asSequence()
        var currentSession = resolveFetchSession(cx)
        _taskRunner = StreamingTaskRunner(urls, currentSession, autoClose = false)

        crawlJob = scope.launch {
            supervisorScope {
                started.countDown()

                // Keep a working crawler for as long as the loop is started:
                // if the task runner exits unexpectedly (e.g. an exception
                // escaped the main loop or a global illegal state was set),
                // restart it. Without this, the loop stays marked as "running"
                // while nothing consumes the url pool, and every newly
                // submitted task stays queued forever.
                while (running.get() && cx.isActive) {
                    // The swarm session may have been closed and recreated;
                    // re-resolve it so a fresh runner is bound to the live one.
                    currentSession = cx.sessions.values
                        .firstOrNull { it.label == SWARM_SESSION_LABEL && it.isActive }
                        ?: currentSession

                    // clear the global illegal states, so the newly created crawler can work properly
                    StreamingTaskRunner.clearIllegalState()

                    // A fresh runner per attempt: a used runner keeps its
                    // BREAK flow state and would exit again immediately.
                    val taskRunner = StreamingTaskRunner(urls, currentSession, autoClose = false)
                    _taskRunner = taskRunner

                    val job = launch { taskRunner.run(this) }
                    job.join()

                    if (running.get() && cx.isActive) {
                        logger.warn(
                            "Task runner #{} exited unexpectedly, restarting in {}s | {}",
                            taskRunner.id, 2, taskRunner
                        )
                        delay(2000)
                    }
                }
            }
        }
    }

    /**
     * Resolve the session this loop fetches the submitted URLs through.
     *
     * Every session shares one loop and one URL pool, so a submitted URL is
     * fetched by the *loop's* session, not by the session that submitted it.
     * That session must therefore own no browser tab:
     *
     * [ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher]
     * first looks for a "specified" driver (or browser) on the page, which
     * inherits from the session config — a session bound to a tab makes *every*
     * fetch drive that one tab and bypasses the privacy pool's driver lease.
     * Concurrent fetches then queue behind a single tab instead of leasing one
     * tab each, and used to corrupt each other's captures before the lease
     * existed (issue #592). The pool can serve
     * `browser.context.number` x `browser.max.active.tabs` fetches in parallel
     * (2 x 8 by default), which is the throughput a bulk crawl expects.
     *
     * The shared swarm session is such a tab-free session by design, so it is
     * used when it exists (that is also how `swarm` keeps its parallelism).
     * Otherwise the loop owns a dedicated tab-free session — a session created
     * by a caller (e.g. one crawl round) must not be adopted: it can be closed
     * as soon as that round completes, leaving the loop without a live session.
     * */
    internal fun resolveFetchSession(context: AbstractPulsarContext): PulsarSession {
        val sessions = context.sessions.values

        // A session that owns a tab is never usable here, whatever its label:
        // tabs are bound lazily (the first `open`/`goto` on the swarm session
        // binds one), so "the swarm session" is not tab-free *by construction*,
        // only by design.  Adopting a tab-bound one would re-serialize every
        // submitted URL behind that single tab.
        fun PulsarSession.ownsNoTab() = boundDriver == null && boundBrowser == null

        val swarm = sessions.firstOrNull { it.label == SWARM_SESSION_LABEL && it.isActive }
        if (swarm != null) {
            if (swarm.ownsNoTab()) return swarm
            val owner = swarm.boundDriver?.let { "driver #${it.id}" }
                ?: swarm.boundBrowser?.let { "browser ${it.id}" }
                ?: "unknown"
            logger.info(
                "Swarm session #{} is bound to a browser tab ({}); the crawl loop will not fetch " +
                        "submitted URLs through it — that would serialize them on one tab",
                swarm.id, owner
            )
        }

        sessions.firstOrNull { it.label == FETCH_SESSION_LABEL && it.isActive && it.ownsNoTab() }
            ?.let { return it }

        val tabOwners = sessions.filter { !it.ownsNoTab() }
        logger.info(
            "Creating a tab-free fetch session for the crawl loop; {} session(s) bound to a browser tab " +
                    "are never used to fetch submitted URLs: {}",
            tabOwners.size, tabOwners.joinToString { "#${it.id}(${it.label.ifBlank { "-" }})" }
        )

        return context.createSession(
            PulsarSettings(profileMode = BrowserProfileMode.SEQUENTIAL, label = FETCH_SESSION_LABEL)
        )
    }

    private fun getOrCreateUrlFeeder(): UrlFeeder {
        val feeder = urlFeeders.values.firstOrNull() ?: createUrlFeeder()
        urlFeeders[feeder.id] = feeder

        // check if the feeder is upgraded
        if (feeder.urlPool.id == context.globalCache.urlPool.id) {
            return feeder
        }

        if (feeder.isNotEmpty()) {
            logger.warn("The url feeder is abundant, but not empty | #{} | size: {}", feeder.id, feeder.size)
            logger.warn(feeder.report)
        }

        // the feeder is upgraded, so we need to create a new one
        val upgradedFeeder = createUrlFeeder()
        urlFeeders.remove(feeder.id)
        urlFeeders[upgradedFeeder.id] = upgradedFeeder

        logger.warn(
            "The url feeder is upgraded, use the new one instead | #{} <- #{} | {}",
            upgradedFeeder.id,
            feeder.id,
            this
        )

        return upgradedFeeder
    }

    /**
     * Create a UrlFeeder with default data collectors.
     * A UrlFeeder is a wrapper to globalCache.urlPool, to clear all the urls that waiting for fetching,
     * use globalCache.urlPool.clear()
     * */
    private fun createUrlFeeder(): UrlFeeder {
        val enableDefaults = config.getBoolean(CRAWL_ENABLE_DEFAULT_DATA_COLLECTORS, true)
        return UrlFeeder(context.globalCache.urlPool, enableDefaults = enableDefaults)
    }

    companion object {
        /**
         * Label of the tab-free session the loop owns when no swarm session exists.
         * */
        const val FETCH_SESSION_LABEL = "FETCH"
    }
}
