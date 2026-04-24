package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSentExtraInfo
import ai.platon.cdt.kt.protocol.events.network.ResponseReceivedExtraInfo
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * There are four possible orders of events:
 * A. `onRequestWillBeSent`
 * B. `onRequestWillBeSent`, `onRequestPaused`
 * C. `onRequestPaused`, `onRequestWillBeSent`
 * D. `onRequestPaused`, `onRequestWillBeSent`, `onRequestPaused`,
 * `onRequestWillBeSent`, `onRequestPaused`, `onRequestPaused`
 * (see crbug.com/1196004)
 *
 * For `onRequest` we need the event from `onRequestWillBeSent` and
 * optionally the `interceptionId` from `onRequestPaused`.
 *
 * If request interception is disabled, call `onRequest` once per call to
 * `onRequestWillBeSent`.
 * If request interception is enabled, call `onRequest` once per call to
 * `onRequestPaused` (once per `interceptionId`).
 *
 * Events are stored to allow for subsequent events to call `onRequest`.
 *
 * Note that (chains of) redirect requests have the same `requestId` (!) as
 * the original request. We have to anticipate series of events like these:
 * A. `onRequestWillBeSent`,
 * `onRequestWillBeSent`, ...
 * B. `onRequestWillBeSent`, `onRequestPaused`,
 * `onRequestWillBeSent`, `onRequestPaused`, ...
 * C. `onRequestWillBeSent`, `onRequestPaused`,
 * `onRequestPaused`, `onRequestWillBeSent`, ...
 * D. `onRequestPaused`, `onRequestWillBeSent`,
 * `onRequestPaused`, `onRequestWillBeSent`, `onRequestPaused`,
 * `onRequestWillBeSent`, `onRequestPaused`, `onRequestPaused`, ...
 * (see crbug.com/1196004)
 */
class NetworkEventManager {
    private val requestWillBeSentEvents = ConcurrentHashMap<NetworkRequestId, RequestWillBeSent>()
    private val requestWillBeSentExtraInfoEvents = ConcurrentHashMap<String, Queue<RequestWillBeSentExtraInfo>>()
    private val requestPausedEvents = ConcurrentHashMap<NetworkRequestId, RequestPaused>()
    private val requests = ConcurrentHashMap<NetworkRequestId, CDPRequest>()

    /*
     * The below maps are used to reconcile Network.responseReceivedExtraInfo
     * events with their corresponding request. Each response and redirect
     * response gets an ExtraInfo event, and we don't know which will come first.
     * This means that we have to store a Response or an ExtraInfo for each
     * response, and emit the event when we get both of them. In addition, to
     * handle redirects, we have to make them Arrays to represent the chain of
     * events.
     */
    private val responseReceivedExtraInfoEvents = ConcurrentHashMap<NetworkRequestId, MutableList<ResponseReceivedExtraInfo>>()
    private val queuedRedirectInfoEvents = ConcurrentHashMap<FetchRequestId, MutableList<RedirectInfo>>()
    private val queuedEventGroups = ConcurrentHashMap<NetworkRequestId, QueuedEventGroup>()

    /**
     * Forget all events
     * */
    fun removeAll(requestId: NetworkRequestId) {
        requestWillBeSentEvents.remove(requestId)
        requestPausedEvents.remove(requestId)
        queuedEventGroups.remove(requestId)
        queuedRedirectInfoEvents.remove(requestId)
        responseReceivedExtraInfoEvents.remove(requestId)
    }

    fun queueRedirectInfoEvent(requestId: FetchRequestId, redirectInfo: RedirectInfo) {
        computeRedirectInfoQueue(requestId).add(redirectInfo)
    }

    fun takeFirstRedirectInfoEvent(requestId: FetchRequestId): RedirectInfo? {
        return computeRedirectInfoQueue(requestId).removeFirstOrNull()
    }

    private fun computeRedirectInfoQueue(requestId: FetchRequestId): MutableList<RedirectInfo> {
        return queuedRedirectInfoEvents.computeIfAbsent(requestId) { LinkedList() }
    }

    fun addRequestWillBeSentExtraInfoEvent(event: RequestWillBeSentExtraInfo) {
        val queue = requestWillBeSentExtraInfoEvents.computeIfAbsent(event.requestId) { LinkedList() }
        queue.add(event)
    }

    fun getRequestWillBeSentExtraInfoEvent(requestId: String): RequestWillBeSentExtraInfo? {
        val queue = requestWillBeSentExtraInfoEvents[requestId]
        return queue?.peek()
    }

    fun takeFirstRequestWillBeSentExtraInfoEvent(requestId: String): RequestWillBeSentExtraInfo? {
        val queue = requestWillBeSentExtraInfoEvents[requestId]
        return queue?.poll()
    }

    fun addRequestWillBeSentEvent(networkRequestId: NetworkRequestId, event: RequestWillBeSent) {
        requestWillBeSentEvents[networkRequestId] = event
    }

    fun getRequestWillBeSentEvent(networkRequestId: NetworkRequestId): RequestWillBeSent? {
        return requestWillBeSentEvents[networkRequestId]
    }

    fun removeRequestWillBeSentEvent(networkRequestId: NetworkRequestId): RequestWillBeSent? {
        return requestWillBeSentEvents.remove(networkRequestId)
    }

    fun addRequestPausedEvent(requestId: NetworkRequestId, event: RequestPaused) {
        requestPausedEvents[requestId] = event
    }

    fun getRequestPausedEvent(requestId: NetworkRequestId) = requestPausedEvents[requestId]

    fun removeRequestPausedEvent(requestId: NetworkRequestId) {
        requestPausedEvents.remove(requestId)
    }


    fun addRequest(requestId: NetworkRequestId, request: CDPRequest) {
        requests[requestId] = request
    }

    fun getCDPRequest(requestId: NetworkRequestId) = requests[requestId]

    fun removeRequest(requestId: String) {
        requests.remove(requestId)
    }


    fun addResponseExtraInfoEvent(requestId: NetworkRequestId, event: ResponseReceivedExtraInfo) {
        computeResponseExtraInfoList(requestId).add(event)
    }

    fun computeResponseExtraInfoList(requestId: NetworkRequestId): MutableList<ResponseReceivedExtraInfo> {
        return responseReceivedExtraInfoEvents.computeIfAbsent(requestId) { LinkedList() }
    }

    fun takeFirstResponseExtraInfo(requestId: NetworkRequestId): ResponseReceivedExtraInfo? {
        return computeResponseExtraInfoList(requestId).removeFirstOrNull()
    }


    fun addQueuedEventGroup(requestId: NetworkRequestId, event: QueuedEventGroup) {
        queuedEventGroups[requestId] = event
    }

    fun getQueuedEventGroup(requestId: NetworkRequestId) = queuedEventGroups[requestId]

    fun deleteQueuedEventGroup(requestId: NetworkRequestId) {
        queuedEventGroups.remove(requestId)
    }
}
