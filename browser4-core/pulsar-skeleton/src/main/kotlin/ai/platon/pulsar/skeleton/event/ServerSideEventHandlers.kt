package ai.platon.pulsar.skeleton.event

import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

/**
 * Server-side event data structure.
 *
 * @property eventType The type of the event (e.g., "onWillLoad", "onFetched", "onHTMLDocumentParsed").
 * @property eventPhase The phase of the event (e.g., "crawl", "load", "browse").
 * @property url The URL associated with the event, if applicable.
 * @property message Optional message or description of the event.
 * @property timestamp The timestamp when the event was created.
 * @property metadata Additional metadata associated with the event.
 */
data class ServerSideEvent(
    val eventType: String,
    val eventPhase: String,
    val url: String? = null,
    val message: String? = null,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Server-side event handlers for capturing and broadcasting events during the page lifecycle.
 *
 * This interface defines methods for receiving events from various phases (crawl, load, browse)
 * and forwarding them to subscribed listeners through a reactive stream.
 *
 * Events can be emitted at any point during the page processing lifecycle and will be
 * automatically forwarded to all subscribers through the event flow.
 *
 * ## Example Usage
 * ```kotlin
 * val eventHandlers = DefaultServerSideEventHandlers()
 *
 * // Subscribe to events
 * eventHandlers.eventFlow.collect { event ->
 *     println("Received event: ${event.eventType} at ${event.url}")
 * }
 *
 * // Emit events during page processing
 * eventHandlers.onCrawlEvent("onWillLoad", "https://example.com")
 * eventHandlers.onLoadEvent("onFetched", page)
 * ```
 *
 * @see ServerSideEvent for the event data structure
 * @see PulsarEventBus for integration with the global event system
 */
interface ServerSideEventHandlers {
    /**
     * The shared flow of server-side events.
     * Subscribers can collect from this flow to receive all events.
     */
    val eventFlow: SharedFlow<ServerSideEvent>

    /**
     * Emits a crawl-phase event.
     *
     * @param eventType The type of the event (e.g., "onWillLoad", "onLoaded").
     * @param url The URL associated with the event.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onCrawlEvent(
        eventType: String,
        url: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a load-phase event.
     *
     * @param eventType The type of the event (e.g., "onWillFetch", "onFetched", "onHTMLDocumentParsed").
     * @param page The web page associated with the event.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onLoadEvent(
        eventType: String,
        page: WebPage,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a browse-phase event.
     *
     * @param eventType The type of the event (e.g., "onBrowserLaunched", "onNavigated", "onDocumentSteady").
     * @param page The web page associated with the event.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onBrowseEvent(
        eventType: String,
        page: WebPage,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a generic event with custom phase.
     *
     * @param eventType The type of the event.
     * @param eventPhase The phase of the event.
     * @param url The URL associated with the event.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onEvent(
        eventType: String,
        eventPhase: String,
        url: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )
}

/**
 * Default implementation of ServerSideEventHandlers.
 *
 * This implementation uses a [MutableSharedFlow] to broadcast events to all subscribers.
 * Events are emitted asynchronously and will not block the event producer.
 *
 * The shared flow is configured with:
 * - replay = 100: New subscribers receive the last 100 events (to handle late subscriptions)
 * - extraBufferCapacity = 64: Buffer up to 64 additional events if consumers are slow
 *
 * @see ServerSideEventHandlers for interface documentation
 */
class DefaultServerSideEventHandlers(
    replay: Int = 100,
    extraBufferCapacity: Int = 64
) : ServerSideEventHandlers {
    private val _eventFlow = MutableSharedFlow<ServerSideEvent>(
        replay = replay,
        extraBufferCapacity = extraBufferCapacity
    )

    override val eventFlow: SharedFlow<ServerSideEvent> = _eventFlow.asSharedFlow()

    override suspend fun onCrawlEvent(
        eventType: String,
        url: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(ServerSideEvent(
            eventType = eventType,
            eventPhase = "crawl",
            url = url,
            message = message,
            metadata = metadata
        ))
    }

    override suspend fun onLoadEvent(
        eventType: String,
        page: WebPage,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(ServerSideEvent(
            eventType = eventType,
            eventPhase = "load",
            url = page.url,
            message = message,
            metadata = metadata + mapOf("pageStatus" to page.protocolStatus.toString())
        ))
    }

    override suspend fun onBrowseEvent(
        eventType: String,
        page: WebPage,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(ServerSideEvent(
            eventType = eventType,
            eventPhase = "browse",
            url = page.url,
            message = message,
            metadata = metadata + mapOf("pageStatus" to page.protocolStatus.toString())
        ))
    }

    override suspend fun onEvent(
        eventType: String,
        eventPhase: String,
        url: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(ServerSideEvent(
            eventType = eventType,
            eventPhase = eventPhase,
            url = url,
            message = message,
            metadata = metadata
        ))
    }

    private suspend fun emitEvent(event: ServerSideEvent) {
        _eventFlow.emit(event)
    }
}
