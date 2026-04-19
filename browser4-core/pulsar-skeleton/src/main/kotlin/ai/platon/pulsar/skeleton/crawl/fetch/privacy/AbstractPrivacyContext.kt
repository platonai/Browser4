package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.HtmlIntegrity
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.common.proxy.ProxyRetiredException
import ai.platon.pulsar.common.proxy.ProxyVendorException
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.crawl.fetch.FetchResult
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.WebDriverFetcher
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserErrorPageException
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.google.common.annotations.Beta
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractPrivacyContext(
    override val profile: BrowserProfile,
    val conf: ImmutableConfig
) : PrivacyContext, Comparable<PrivacyContext> {
    companion object {
        private val SEQUENCER = AtomicInteger()

        val PRIVACY_CONTEXT_IDLE_TIMEOUT_DEFAULT: Duration = Duration.ofMinutes(30)

        val globalMetrics by lazy { PrivacyContextMetrics() }
    }

    private val logger = LoggerFactory.getLogger(PrivacyContext::class.java)
    private val throttlingLogger = ThrottlingLogger(logger)

    var webdriverFetcher: WebDriverFetcher? = null

    override val id get() = profile.id
    val seq = SEQUENCER.incrementAndGet()
    override val display get() = profile.display
    val baseDir get() = profile.contextDir

    protected val numRunningTasks = AtomicInteger()
    val minimumThroughput =
        if (profile.isPermanent) 0f else conf.getFloat(CapabilityTypes.PRIVACY_CONTEXT_MIN_THROUGHPUT, 0.3f)
    val maximumWarnings = if (profile.isPermanent) 100000 else conf.getInt(CapabilityTypes.PRIVACY_MAX_WARNINGS, 8)
    val minorWarningFactor = conf.getInt(CapabilityTypes.PRIVACY_MINOR_WARNING_FACTOR, 5)
    val privacyLeakWarnings = AtomicInteger()
    val privacyLeakMinorWarnings = AtomicInteger()

    private val registry = MetricsSystem.defaultMetricRegistry
    private val sms = MetricsSystem.SHADOW_METRIC_SYMBOL
    val meterTasks = registry.meter(this, "$SEQUENCER$sms", "tasks")
    val meterSuccesses = registry.meter(this, "$SEQUENCER$sms", "successes")
    val meterFinishes = registry.meter(this, "$SEQUENCER$sms", "finishes")
    val meterSmallPages = registry.meter(this, "$SEQUENCER$sms", "smallPages")
    val smallPageRate get() = 1.0 * meterSmallPages.count / meterTasks.count.coerceAtLeast(1)
    val successRate = meterSuccesses.count.toFloat() / meterTasks.count

    /**
     * The rate of failures. Failure rate is meaningless when there are few tasks.
     * */
    override val failureRate get() = 1 - successRate
    val failureRateThreshold = conf.getFloat(CapabilityTypes.PRIVACY_CONTEXT_FAILURE_RATE_THRESHOLD, 0.6f)

    /**
     * Check if failure rate is too high.
     * High failure rate make sense only when there are many tasks.
     * */
    override val isHighFailureRate get() = meterTasks.count > 100 && failureRate > failureRateThreshold

    /**
     * The start time of the privacy context.
     * */
    val startTime = Instant.now()

    /**
     * The last active time of the privacy context.
     * */
    var lastActiveTime = Instant.now()
        private set

    /**
     * The elapsed time of the privacy context since it's started.
     * */
    override val elapsedTime get() = Duration.between(startTime, Instant.now())

    private val fetchTaskTimeout
        get() = conf.getDuration(CapabilityTypes.FETCH_TASK_TIMEOUT, AppConstants.FETCH_TASK_TIMEOUT_DEFAULT)
    private val privacyContextIdleTimeout
        get() = conf.getDuration(CapabilityTypes.PRIVACY_CONTEXT_IDLE_TIMEOUT, PRIVACY_CONTEXT_IDLE_TIMEOUT_DEFAULT)
    private val idleTimeout: Duration get() = privacyContextIdleTimeout.coerceAtLeast(fetchTaskTimeout)

    private val isLeaked0: Boolean get() {
        return !profile.isPermanent && privacyLeakWarnings.get() >= maximumWarnings
    }

    private val isActive0: Boolean get() {
        return !isClosed && !isLeaked && !isRetired
    }

    /**
     * The privacy context is retired, and should be closed soon.
     * */
    protected var retired = false
    /**
     * The idle time of the privacy context.
     * */
    override val idleTime get() = Duration.between(lastActiveTime, Instant.now())
    /**
     * Whether the privacy context is idle.
     * */
    override val isIdle get() = idleTime > idleTimeout

//    val historyUrls = PassiveExpiringMap<String, String>()
    /**
     * Whether the privacy context is closed.
     * */
    protected val closed = AtomicBoolean()

    override val isGood get() = meterSuccesses.meanRate >= minimumThroughput

    override val isLeaked: Boolean get() {
        val leaked = isLeaked0
        if (leaked) {
            throttlingLogger.warn("Privacy context is leaked | {}", state)
        }
        return leaked
    }

    override val isRetired get() = retired

    override val isActive: Boolean get() = isActive0

    override val isClosed get() = closed.get()

    override val isReady get() = hasWebDriverPromise() && isActive0

    override val isFullCapacity = false

    override val isUnderLoaded get() = !isFullCapacity

    override val state: Map<String, Any?> get() {
        return mapOf(
            "id" to id, "seq" to seq, "display" to display, "startTime" to startTime,
            "closed" to isClosed, "leaked" to isLeaked, "active" to isActive0,
            "highFailure" to isHighFailureRate, "idle" to isIdle, "good" to isGood,
            "ready" to isReady, "retired" to isRetired
        )
    }

    override val readableState: String get() = formatState()

    init {
        globalMetrics.contexts.mark()
    }

    fun formatState(): String {
        val booleanStates = state.entries.filter { it.value is Boolean }.filter { it.value == true }
            .sortedBy { it.key }
            .joinToString(",") { it.key }
        val otherStates = state.entries.filter { it.value !is Boolean }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
        return "{$booleanStates,$otherStates}"
    }

    abstract override fun promisedWebDriverCount(): Int

    /**
     * Check if the privacy context promises at least one worker to provide.
     * */
    override fun hasWebDriverPromise() = promisedWebDriverCount() > 0

    @Beta
    abstract fun subscribeWebDriver(): WebDriver?

    /**
     * Marks a task as successful and updates relevant metrics.
     * */
    fun markSuccess() {
        privacyLeakWarnings.takeIf { it.get() > 0 }?.decrementAndGet()
        meterSuccesses.mark()
        globalMetrics.successes.mark()
    }

    /**
     * Mark a warning.
     * */
    fun markWarning() {
        privacyLeakWarnings.incrementAndGet()
        globalMetrics.leakWarnings.mark()
    }

    /**
     * Mark n warnings.
     * */
    fun markWarning(n: Int) {
        privacyLeakWarnings.addAndGet(n)
        globalMetrics.leakWarnings.mark(n.toLong())
    }

    /**
     * Mark a minor warnings.
     * */
    fun markMinorWarning() {
        privacyLeakMinorWarnings.incrementAndGet()
        globalMetrics.minorLeakWarnings.mark()
        if (privacyLeakMinorWarnings.get() > minorWarningFactor) {
            privacyLeakMinorWarnings.set(0)
            markWarning()
        }
    }

    /**
     * Mark the privacy context as leaked. A leaked privacy context should not serve anymore,
     * and will be closed soon.
     * */
    fun markLeaked() {
        if (profile.isPermanent) {
            // never mark a permanent privacy context as leaked
        } else {
            require(maximumWarnings in 1..1000000) {
                "The maximum warnings should be set to a reasonable value, but not $maximumWarnings"
            }
            privacyLeakWarnings.addAndGet(maximumWarnings)
        }
    }

    /**
     * Open a url in the privacy context.
     * */
    abstract override suspend fun open(url: String): FetchResult

    /**
     * Open a url in the privacy context, with the specified options.
     * */
    abstract override suspend fun open(url: String, options: LoadOptions): FetchResult

    /**
     * Open a url in the privacy context, with the specified fetch function.
     * */
    @Throws(ProxyVendorException::class)
    override suspend fun open(url: String, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        val task = FetchTask.create(url, conf.toVolatileConfig())
        return run(task, fetchFun)
    }

    /**
     * Run a task in the privacy context, with the specified fetch function.
     *
     * @param task the fetch task
     * @param fetchFun the fetch function
     * @return the fetch result
     * */
    @Throws(ProxyVendorException::class, Exception::class)
    override suspend fun run(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult {
        beforeRun(task)
        val result = doRun(task, fetchFun)
        afterRun(result)
        return result
    }

    /**
     * Run a task in the privacy context.
     *
     * @param task the fetch task
     * @param fetchFun the fetch function
     * @return the fetch result
     * */
    @Throws(Exception::class)
    abstract override suspend fun doRun(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult

    override fun buildStatusString(): String {
        return "$readableState | promised drivers: ${promisedWebDriverCount()}"
    }
    /**
     * Dismisses the privacy context and marks it as retired, indicating that it should be closed later.
     * This function sets the `retired` flag to `true`, signaling that the privacy context is no longer active
     * and should be handled accordingly (e.g., closed or cleaned up).
     *
     * This function does not take any parameters and does not return any value.
     */
    override fun dismiss() {
        retired = true
    }
    /**
     * Do the maintaining jobs.
     * */
    abstract override fun maintain()

    override fun compareTo(other: PrivacyContext) = id.compareTo(other.id)

    override fun equals(other: Any?) = other is PrivacyContext && other.id == id

    override fun hashCode() = id.hashCode()

    protected fun beforeRun(task: FetchTask) {
        lastActiveTime = Instant.now()
        meterTasks.mark()
        globalMetrics.tasks.mark()

        numRunningTasks.incrementAndGet()
    }

    protected fun afterRun(result: FetchResult) {
        numRunningTasks.decrementAndGet()
//        historyUrls.add(result.task.url)

        lastActiveTime = Instant.now()
        meterFinishes.mark()
        globalMetrics.finishes.mark()

        val status = result.status
        when {
            status.isRetry(RetryScope.PRIVACY, ProxyRetiredException::class.java) -> markLeaked()
            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.FORBIDDEN) -> markLeaked()

            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.ROBOT_CHECK) -> markWarning()
            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.ROBOT_CHECK_2) -> markWarning(2)
            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.ROBOT_CHECK_3) -> markWarning(3)

            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.WRONG_LANG) -> markWarning(2)
            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.WRONG_DISTRICT) -> markWarning(2)
            status.isRetry(RetryScope.PRIVACY, HtmlIntegrity.WRONG_COUNTRY) -> markWarning(2)

            status.isRetry(RetryScope.PRIVACY, BrowserErrorPageException::class.java) -> markWarning(3)
            status.isRetry(RetryScope.PRIVACY) -> markWarning()
            status.isRetry(RetryScope.CRAWL) -> markMinorWarning()
            status.isSuccess -> markSuccess()
        }

        if (result.isSmall) {
            meterSmallPages.mark()
            globalMetrics.smallPages.mark()
        }

        if (isLeaked) {
            globalMetrics.contextLeaks.mark()
        }
    }

    override fun buildReport(): String {
        return String.format("Privacy context #%s has lived for %s", SEQUENCER, elapsedTime.readable())
    }

    open fun report() {
        logger.info("Privacy context #{} has lived for {}", SEQUENCER, elapsedTime.readable())
    }
}
