package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * Event filter function type for filtering events based on custom conditions.
 *
 * @param eventType The type of event being filtered
 * @param eventPhase The phase of the event (crawl, load, browse)
 * @param url The URL associated with the event, if any
 * @return true if the event should be processed, false to skip it
 */
typealias EventFilter = (eventType: String, eventPhase: String, url: String?) -> Boolean

/**
 * Event history record containing details of a triggered event.
 *
 * @property eventType The type of event
 * @property eventPhase The phase of the event
 * @property url The URL associated with the event
 * @property message Optional message
 * @property timestamp When the event occurred
 * @property metadata Additional metadata
 */
data class EventHistoryRecord(
    val eventType: String,
    val eventPhase: String,
    val url: String? = null,
    val message: String? = null,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Configuration for event history recording.
 *
 * @property enabled Whether history recording is enabled
 * @property maxSize Maximum number of history records to keep (0 = unlimited)
 * @property filter Optional filter to determine which events to record
 */
data class EventHistoryConfig(
    val enabled: Boolean = false,
    val maxSize: Int = 1000,
    val filter: EventFilter? = null
)

/**
 * The global event handlers with enhanced features including:
 * - Thread-safe scoped handler management
 * - Event filtering for conditional triggering
 * - Event history recording for debugging and auditing
 *
 * ## Basic Usage
 * ```kotlin
 * // Set global handlers
 * GlobalEventHandlers.pageEventHandlers = myHandlers
 *
 * // Use scoped handlers (auto-restored)
 * GlobalEventHandlers.withHandlers(tempHandlers) {
 *     session.load(url, options)
 * }
 * ```
 *
 * ## Event Filtering
 * ```kotlin
 * // Filter to only process specific event types
 * GlobalEventHandlers.eventFilter = { eventType, phase, url ->
 *     eventType in setOf("onWillLoad", "onLoaded")
 * }
 * ```
 *
 * ## Event History
 * ```kotlin
 * // Enable history recording
 * GlobalEventHandlers.configureHistory(
 *     EventHistoryConfig(
 *         enabled = true,
 *         maxSize = 500,
 *         filter = { type, _, _ -> type.startsWith("on") }
 *     )
 * )
 *
 * // Query history
 * val recentEvents = GlobalEventHandlers.getEventHistory(limit = 10)
 * ```
 * */
object GlobalEventHandlers {
    /**
     * Thread-safe reference to page event handlers.
     *
     * The calling order rule:
     * The more specific handlers has the opportunity to override the result of more general handlers.
     * */
    @PublishedApi
    internal val pageEventHandlersRef = AtomicReference<PageEventHandlers?>(null)

    /**
     * Thread-safe reference to server-side event handlers for broadcasting events to external listeners.
     *
     * When set, events from page event handlers will be forwarded to this handler,
     * which can broadcast them to clients via SSE or other mechanisms.
     * */
    @PublishedApi
    internal val serverSideEventHandlersRef = AtomicReference<ServerSideEventHandlers?>(null)

    /**
     * Thread-safe reference to the event filter for conditional event triggering.
     */
    private val eventFilterRef = AtomicReference<EventFilter?>(null)

    /**
     * Thread-safe reference to event history configuration.
     */
    private val historyConfigRef = AtomicReference(EventHistoryConfig())

    /**
     * Thread-safe deque for storing event history.
     */
    private val eventHistory = ConcurrentLinkedDeque<EventHistoryRecord>()

    /**
     * Background coroutine scope for non-blocking event emission.
     * Uses Dispatchers.Default for CPU-bound work and SupervisorJob to isolate failures.
     */
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Gets or sets the page event handlers.
     */
    var pageEventHandlers: PageEventHandlers?
        get() = pageEventHandlersRef.get()
        set(value) = pageEventHandlersRef.set(value)

    /**
     * Gets or sets the server-side event handlers.
     */
    var serverSideEventHandlers: ServerSideEventHandlers?
        get() = serverSideEventHandlersRef.get()
        set(value) = serverSideEventHandlersRef.set(value)

    /**
     * Gets or sets the event filter for conditional event triggering.
     *
     * When set, only events that pass the filter will be processed.
     *
     * ## Example
     * ```kotlin
     * // Only process load events
     * GlobalEventHandlers.eventFilter = { eventType, phase, _ ->
     *     phase == "load"
     * }
     *
     * // Only process events for specific domains
     * GlobalEventHandlers.eventFilter = { _, _, url ->
     *     url?.contains("example.com") == true
     * }
     * ```
     */
    var eventFilter: EventFilter?
        get() = eventFilterRef.get()
        set(value) = eventFilterRef.set(value)

    /**
     * Configures event history recording.
     *
     * @param config The history configuration
     */
    fun configureHistory(config: EventHistoryConfig) {
        historyConfigRef.set(config)
        if (!config.enabled) {
            eventHistory.clear()
        }
    }

    /**
     * Gets the current event history configuration.
     */
    fun getHistoryConfig(): EventHistoryConfig = historyConfigRef.get()

    /**
     * Retrieves event history records.
     *
     * @param limit Maximum number of records to return (0 = all)
     * @param eventType Optional filter by event type
     * @param eventPhase Optional filter by event phase
     * @return List of history records, most recent first
     */
    fun getEventHistory(
        limit: Int = 0,
        eventType: String? = null,
        eventPhase: String? = null
    ): List<EventHistoryRecord> {
        var records = eventHistory.toList().asReversed()

        if (eventType != null) {
            records = records.filter { it.eventType == eventType }
        }
        if (eventPhase != null) {
            records = records.filter { it.eventPhase == eventPhase }
        }

        return if (limit > 0) records.take(limit) else records
    }

    /**
     * Clears all event history.
     */
    fun clearEventHistory() {
        eventHistory.clear()
    }

    /**
     * Executes a block with temporary page event handlers, automatically restoring the previous handlers.
     *
     * This provides a thread-safe way to use scoped handlers without manual cleanup.
     *
     * ## Example
     * ```kotlin
     * GlobalEventHandlers.withHandlers(tempHandlers) {
     *     session.load(url, options)
     *     // tempHandlers are active here
     * }
     * // Previous handlers automatically restored
     * ```
     *
     * @param handlers The temporary handlers to use
     * @param block The code block to execute with the temporary handlers
     * @return The result of the block execution
     */
    inline fun <T> withHandlers(handlers: PageEventHandlers?, block: () -> T): T {
        val previous = pageEventHandlersRef.getAndSet(handlers)
        try {
            return block()
        } finally {
            pageEventHandlersRef.set(previous)
        }
    }

    /**
     * Executes a block with temporary server-side event handlers, automatically restoring the previous handlers.
     *
     * This provides a thread-safe way to use scoped server-side handlers without manual cleanup.
     *
     * ## Example
     * ```kotlin
     * val customHandlers = DefaultServerSideEventHandlers()
     * GlobalEventHandlers.withServerSideHandlers(customHandlers) {
     *     session.load(url, options)
     *     // customHandlers are active here
     * }
     * // Previous handlers automatically restored
     * ```
     *
     * @param handlers The temporary server-side handlers to use
     * @param block The code block to execute with the temporary handlers
     * @return The result of the block execution
     */
    inline fun <T> withServerSideHandlers(handlers: ServerSideEventHandlers?, block: () -> T): T {
        val previous = serverSideEventHandlersRef.getAndSet(handlers)
        try {
            return block()
        } finally {
            serverSideEventHandlersRef.set(previous)
        }
    }

    /**
     * Executes a block with both temporary page and server-side handlers.
     *
     * ## Example
     * ```kotlin
     * GlobalEventHandlers.withBothHandlers(pageHandlers, serverHandlers) {
     *     session.load(url, options)
     * }
     * ```
     *
     * @param pageHandlers The temporary page handlers to use
     * @param serverHandlers The temporary server-side handlers to use
     * @param block The code block to execute
     * @return The result of the block execution
     */
    inline fun <T> withBothHandlers(
        pageHandlers: PageEventHandlers?,
        serverHandlers: ServerSideEventHandlers?,
        block: () -> T
    ): T {
        return withHandlers(pageHandlers) {
            withServerSideHandlers(serverHandlers) {
                block()
            }
        }
    }

    /**
     * Checks if an event should be processed based on the current event filter.
     *
     * @param eventType The type of event
     * @param eventPhase The phase of the event
     * @param url The URL associated with the event
     * @return true if the event should be processed
     */
    private fun shouldProcessEvent(eventType: String, eventPhase: String, url: String?): Boolean {
        val filter = eventFilterRef.get() ?: return true
        return filter(eventType, eventPhase, url)
    }

    /**
     * Records an event to history if history recording is enabled.
     *
     * @param eventType The type of event
     * @param eventPhase The phase of the event
     * @param url The URL associated with the event
     * @param message Optional message
     * @param metadata Additional metadata
     */
    private fun recordEvent(
        eventType: String,
        eventPhase: String,
        url: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        val config = historyConfigRef.get()
        if (!config.enabled) return

        // Check history filter
        val historyFilter = config.filter
        if (historyFilter != null && !historyFilter(eventType, eventPhase, url)) {
            return
        }

        val record = EventHistoryRecord(eventType, eventPhase, url, message, Instant.now(), metadata)
        eventHistory.addLast(record)

        // Enforce max size
        if (config.maxSize > 0) {
            while (eventHistory.size > config.maxSize) {
                eventHistory.removeFirst()
            }
        }
    }

    /**
     * Emits a crawl event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * Events are filtered based on the configured event filter and recorded to history if enabled.
     *
     * @param eventType The type of event
     * @param url The URL associated with the event
     * @param message Optional message
     */
    fun emitCrawlEvent(eventType: String, url: String? = null, message: String? = null) {
        if (!shouldProcessEvent(eventType, "crawl", url)) {
            return
        }

        recordEvent(eventType, "crawl", url, message, emptyMap())

        serverSideEventHandlersRef.get()?.let { handlers ->
            eventScope.launch {
                handlers.onCrawlEvent(eventType, url, message)
            }
        }
    }

    /**
     * Emits a load event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * Events are filtered based on the configured event filter and recorded to history if enabled.
     *
     * @param eventType The type of event
     * @param page The web page associated with the event
     * @param message Optional message
     * @param metadata Additional metadata
     */
    fun emitLoadEvent(eventType: String, page: WebPage, message: String? = null, metadata: Map<String, Any?> = emptyMap()) {
        if (!shouldProcessEvent(eventType, "load", page.url)) {
            return
        }

        recordEvent(eventType, "load", page.url, message, metadata)

        serverSideEventHandlersRef.get()?.let { handlers ->
            eventScope.launch {
                handlers.onLoadEvent(eventType, page, message, metadata)
            }
        }
    }

    /**
     * Emits a browse event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * Events are filtered based on the configured event filter and recorded to history if enabled.
     *
     * @param eventType The type of event
     * @param page The web page associated with the event
     * @param message Optional message
     * @param metadata Additional metadata
     */
    fun emitBrowseEvent(eventType: String, page: WebPage, message: String? = null, metadata: Map<String, Any?> = emptyMap()) {
        if (!shouldProcessEvent(eventType, "browse", page.url)) {
            return
        }

        recordEvent(eventType, "browse", page.url, message, metadata)

        serverSideEventHandlersRef.get()?.let { handlers ->
            eventScope.launch {
                handlers.onBrowseEvent(eventType, page, message, metadata)
            }
        }
    }
}
