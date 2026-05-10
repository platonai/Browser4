package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.browser4.driver.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.events.fetch.AuthRequired
import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.events.network.*
import ai.platon.cdt.kt.protocol.types.fetch.AuthChallengeResponse
import ai.platon.cdt.kt.protocol.types.fetch.AuthChallengeResponseResponse
import ai.platon.cdt.kt.protocol.types.fetch.RequestPattern
import ai.platon.cdt.kt.protocol.types.network.Response
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.event.AbstractEventEmitter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.driver.cdt.Credentials
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.util.*

internal class NetworkManager(
    private val driver: PulsarWebDriver,
    private val rpc: RobustRPC,
) : AbstractEventEmitter<NetworkEvents>() {
    private val logger = getLogger(this)
    private val tracer get() = logger.takeIf { it.isTraceEnabled }

    val isActive get() = driver.isActive

    private val networkAPI get() = driver.devTools.network.takeIf { isActive }
    private val fetchAPI get() = driver.devTools.fetch.takeIf { isActive }
    private val securityAPI get() = driver.devTools.security.takeIf { isActive }

    private val networkEventManager = NetworkEventManager()

    var lastActiveTime = Instant.EPOCH
        private set

    var idleTimeout = Duration.ofSeconds(1)
    val isIdle get() = DateTimes.isExpired(lastActiveTime, idleTimeout)

    // TODO: is it a launch parameter?
    var ignoreHTTPSErrors = true
    val extraHTTPHeaders = mutableMapOf<String, Any>()

    var credentials: Credentials? = null
    val attemptedAuthentications = mutableSetOf<String>()
    var userRequestInterceptionEnabled = false
    var protocolRequestInterceptionEnabled = false
    var userCacheDisabled = false

    init {
        fetchAPI?.onRequestPaused(::onRequestPaused)
        fetchAPI?.onAuthRequired(::onAuthRequired)
        networkAPI?.onRequestWillBeSent(::onRequestWillBeSent)
        networkAPI?.onRequestWillBeSentExtraInfo(::onRequestWillBeSentExtraInfo)
        networkAPI?.onRequestServedFromCache(::onRequestServedFromCache)
        networkAPI?.onResponseReceived(::onResponseReceived)
        networkAPI?.onLoadingFinished(::onLoadingFinished)
        networkAPI?.onLoadingFailed(::onLoadingFailed)
        networkAPI?.onResponseReceivedExtraInfo(::onResponseReceivedExtraInfo)
    }

    suspend fun enable() {
        if (ignoreHTTPSErrors) {
            rpc.invokeSilently("setIgnoreCertificateErrors") {
                securityAPI?.enable()
                securityAPI?.setIgnoreCertificateErrors(ignoreHTTPSErrors)
            }
        }

        rpc.invokeSilently("enable") {
            networkAPI?.enable()
        }
    }

    suspend fun authenticate(credentials: Credentials) {
        this.credentials = credentials
        updateProtocolRequestInterception()
    }

    suspend fun setExtraHTTPHeaders(headers: Map<String, String>) {
        extraHTTPHeaders.clear()
        headers.entries.associateTo(extraHTTPHeaders) { it.key.lowercase() to it.value }

        rpc.invoke("setExtraHTTPHeaders") {
            networkAPI?.setExtraHTTPHeaders(extraHTTPHeaders)
        }
    }

    suspend fun setCacheEnabled(enabled: Boolean) {
        userCacheDisabled = !enabled
        updateProtocolCacheDisabled()
    }

    suspend fun setRequestInterception(value: Boolean) {
        userRequestInterceptionEnabled = value
        updateProtocolRequestInterception()
    }

    private suspend fun onAuthRequired(event: AuthRequired) {
        tracer?.trace("onAuthRequired | {}", event.requestId)

        val response = when {
            attemptedAuthentications.contains(event.requestId) -> AuthChallengeResponseResponse.CANCEL_AUTH
            credentials != null -> AuthChallengeResponseResponse.PROVIDE_CREDENTIALS
            else -> AuthChallengeResponseResponse.DEFAULT
        }
        val authChallengeResponse = AuthChallengeResponse(response, credentials?.username, credentials?.password)

        rpc.invokeSilently("continueWithAuth", event.requestId) {
            fetchAPI?.continueWithAuth(event.requestId, authChallengeResponse)
        }
    }

    private suspend fun onRequestPaused(event: RequestPaused) {
        tracer?.trace("onRequestPaused | {}", event.requestId)

        if (credentials != null) {
            if (!protocolRequestInterceptionEnabled) {
                logger.warn("protocolRequestInterceptionEnabled should be true since credentials is set")
            }
        }

        if (!userRequestInterceptionEnabled && protocolRequestInterceptionEnabled) {
            rpc.invokeSilently("continueRequest", event.requestId) {
                fetchAPI?.continueRequest(event.requestId)
            }
        }

        /**
         * If the intercepted request had a corresponding Network.requestWillBeSent event fired for it,
         * then this networkId will be the same as the requestId present in the requestWillBeSent event.
         */
        val networkRequestId = event.networkId
        val fetchRequestId = event.requestId

        if (networkRequestId == null) {
            onRequestWithoutNetworkInstrumentation(event)
            return
        }

        var requestWillBeSentEvent = computeRequestWillBeSentEvent(networkRequestId, event)
        if (requestWillBeSentEvent != null) {
            requestWillBeSentEvent = patchRequestEventHeaders(requestWillBeSentEvent, event)
            onRequest(requestWillBeSentEvent, fetchRequestId)
        } else {
            networkEventManager.addRequestPausedEvent(networkRequestId, event)
        }
    }

    private fun computeRequestWillBeSentEvent(networkRequestId: String, event: RequestPaused): RequestWillBeSent? {
        val willSentEvent = networkEventManager.getRequestWillBeSentEvent(networkRequestId)
        // redirect requests have the same `requestId`,
        val different = willSentEvent != null &&
                (willSentEvent.request.url != event.request.url || willSentEvent.request.method != event.request.method)

        if (different) {
            networkEventManager.removeRequestWillBeSentEvent(networkRequestId)
        }

        return if (!different) willSentEvent else null
    }

    private fun onRequestWillBeSent(event: RequestWillBeSent) {
        tracer?.trace("onRequestWillBeSent | requestId: {}", event.requestId)
        // Request interception doesn't happen for data URLs with Network Service.

        lastActiveTime = Instant.now()

        // Consider to remove RequestWillBeSent, use emit(NetworkManagerEvents.Request, request)
        emit(NetworkEvents.RequestWillBeSent, event)

        val url = event.request.url
        val intercept = userRequestInterceptionEnabled && !url.startsWith("data:")
        if (!intercept) {
            onRequest(event, null)
            return
        }

        val networkRequestId = event.requestId
        networkEventManager.addRequestWillBeSentEvent(networkRequestId, event)

        /**
         * CDP may have sent a Fetch.requestPaused event already. Check for it.
         */
        val requestPausedEvent = networkEventManager.getRequestPausedEvent(networkRequestId)
        if (requestPausedEvent != null) {
            val fetchRequestId = requestPausedEvent.requestId
            patchRequestEventHeaders(event, requestPausedEvent)
            onRequest(event, fetchRequestId)
            networkEventManager.removeRequestPausedEvent(networkRequestId)
        }
    }

    private fun onRequestWillBeSentExtraInfo(event: RequestWillBeSentExtraInfo) {
        networkEventManager.addRequestWillBeSentExtraInfoEvent(event)
    }

    private fun onRequest(event: RequestWillBeSent, fetchRequestId: String?) {
        val requestId = event.requestId

        tracer?.trace("onRequest | {}", requestId)

        var redirectChain: Queue<WeakReference<CDPRequest>> = LinkedList()
        if (event.redirectResponse != null) {
            // We want to emit a response and RequestFinished for the
            // redirectResponse, but we can't do so unless we have a
            // responseExtraInfo ready to pair it up with. If we don't have any
            // responseExtraInfos saved in our queue, then we have to wait until
            // the next one to emit response and RequestFinished, *and* we should
            // also wait to emit this Request too because it should come after the
            // response/RequestFinished.
            val request = networkEventManager.getCDPRequest(requestId)
            val response = event.redirectResponse
            // If we connect late to the target, we could have missed the requestWillBeSent event.
            if (request != null && response != null) {
                handleRequestRedirect(request, response)
                redirectChain = request.redirectChain
            }
        }

        // TODO: add a frame manager
        val frame = event.frameId

        require(requestId == event.requestId) { "Inconsistent request id: <${event.requestId}> <- <$requestId>" }
        val allowInterception = userRequestInterceptionEnabled
        val request = CDPRequest(driver, requestId, event.request, fetchRequestId, allowInterception, redirectChain)
        request.also {
            it.loaderId = event.loaderId
            it.documentURL = event.documentURL
            it.initiator = event.initiator
            it.type = event.type
        }
        networkEventManager.addRequest(requestId, request)

        emit(NetworkEvents.Request, request)

        request.finalizeInterceptions()
    }

    private fun onRequestServedFromCache(event: RequestServedFromCache) {
        tracer?.trace("onRequestServedFromCache | {}", event.requestId)

        val request = networkEventManager.getCDPRequest(event.requestId)
        request?.fromMemoryCache = true

        emit(NetworkEvents.RequestServedFromCache, request)
    }

    private fun onResponseReceived(event: ResponseReceived) {
        lastActiveTime = Instant.now()

        val requestId = event.requestId

        tracer?.trace("onResponseReceived | {}", requestId)

        emit(NetworkEvents.ResponseReceived, event)

        emitResponseEvent(event)
    }

    private fun onResponseReceivedExtraInfo(event: ResponseReceivedExtraInfo) {
        val requestId = event.requestId
        tracer?.trace("onResponseReceivedExtraInfo | {}", event.requestId)

        // We may have skipped a redirect response/request pair due to waiting for
        // this ExtraInfo event. If so, continue that work now that we have the
        // request.
        val redirectInfo: RedirectInfo? = networkEventManager.takeFirstRedirectInfoEvent(requestId)
        if (redirectInfo != null) {
            networkEventManager.addResponseExtraInfoEvent(requestId, event)
            onRequest(redirectInfo.event, redirectInfo.fetchRequestId)
            return
        }

        // We may have skipped response and loading events because we didn't have
        // this ExtraInfo event yet. If so, emit those events now.
        val queuedEvents = networkEventManager.getQueuedEventGroup(requestId)
        if (queuedEvents != null) {
            networkEventManager.deleteQueuedEventGroup(requestId)
            emitResponseEvent(queuedEvents.responseReceivedEvent)
            queuedEvents.loadingFinishedEvent?.let { emitLoadingFinished(it) }
            queuedEvents.loadingFailedEvent?.let { emitLoadingFailed(it) }
            return
        }

        networkEventManager.addResponseExtraInfoEvent(requestId, event)
    }

    private fun patchRequestEventHeaders(requestWillBeSent: RequestWillBeSent, requestPaused: RequestPaused): RequestWillBeSent {
        // includes extra headers, like: Accept, Origin
        val patchedHeaders = requestWillBeSent.request.headers + requestPaused.request.headers
        val patchedRequest = requestWillBeSent.request.copy(headers = patchedHeaders)
        return requestWillBeSent.copy(request = patchedRequest)
    }

    private fun onRequestWithoutNetworkInstrumentation(event: RequestPaused) {
        // If an event has no networkId it should not have any network events. We still want to dispatch it
        // for the interception by the user.
        val frame = event.frameId
        val interceptionId = event.requestId
        val request = CDPRequest(driver, event.requestId, event.request, interceptionId, userRequestInterceptionEnabled)
        request.also {
//            it.loaderId = event.loaderId
//            it.documentURL = event.documentURL
//            it.initiator = event.initiator
//            it.type = event.type
        }
        emit(NetworkEvents.Request, request)
        request.finalizeInterceptions()
    }

    private fun emitResponseEvent(event: ResponseReceived) {
        val requestId = event.requestId
        val request = networkEventManager.getCDPRequest(requestId) ?: return
        val extraInfos = networkEventManager.computeResponseExtraInfoList(requestId)
        if (extraInfos.isNotEmpty()) {
            logger.debug("Unexpected extraInfo events for request | {} events | {}", extraInfos.size, requestId)
        }

        val response = CDPResponse(driver, request, event.response)
        request.response = response

        emit(NetworkEvents.Response, response)
    }

    private fun handleRequestRedirect(request: CDPRequest, underlyingResponse: Response) {
        val response = CDPResponse(driver, request, underlyingResponse)
        request.response = response
        request.redirectChain.add(WeakReference(request))
        forgetRequest(request, false)

        emit(NetworkEvents.Response, response)
        emit(NetworkEvents.RequestFinished, request)
    }

    private suspend fun updateProtocolRequestInterception() {
        val enabled = userRequestInterceptionEnabled || credentials != null
        if (enabled == protocolRequestInterceptionEnabled) {
            return
        }
        protocolRequestInterceptionEnabled = enabled

        updateProtocolCacheDisabled()

        if (enabled) {
            val pattern = RequestPattern("*")
            rpc.invokeSilently("enable") {
                fetchAPI?.enable(listOf(pattern), true)
            }
        } else {
            // TODO: there are other scenarios to enable FetchAPI
            // fetchAPI?.disable()
        }
    }

    private fun forgetRequest(request: CDPRequest, removeEvents: Boolean) {
        val requestId = request.requestId
        val interceptionId = request.interceptionId

        networkEventManager.removeRequest(requestId)
        if (interceptionId != null) {
            attemptedAuthentications.remove(interceptionId)
        }

        if (removeEvents) {
            networkEventManager.removeAll(requestId)
        }
    }

    private suspend fun updateProtocolCacheDisabled() {
        try {
            rpc.invoke("setCacheDisabled") {
                networkAPI?.setCacheDisabled(this.userCacheDisabled)
            }
        } catch (e: ChromeRPCException) {
            rpc.handleChromeException(e, "setCacheDisabled")
        }
    }

    private fun onLoadingFinished(event: LoadingFinished) {
        val requestId = event.requestId
        tracer?.trace("onLoadingFinished | {}", event.requestId)

        val queuedEventGroup =networkEventManager.getQueuedEventGroup(requestId)
        if (queuedEventGroup != null) {
            queuedEventGroup.loadingFinishedEvent = event
        } else {
            emitLoadingFinished(event)
        }
    }

    private fun emitLoadingFinished(event: LoadingFinished) {
        // For certain requestIds we never receive requestWillBeSent event.
        // @see https://crbug.com/750469
        val request = networkEventManager.getCDPRequest(event.requestId) ?: return

        // Under certain conditions we never get the Network.responseReceived
        // event from protocol. @see https://crbug.com/883475
        request.response?.resolveBody(null)

        forgetRequest(request, true)
        emit(NetworkEvents.RequestFinished, request)
    }

    private fun onLoadingFailed(event: LoadingFailed) {
        val requestId = event.requestId
        tracer?.trace("onLoadingFailed | requestId: {} | {}", event.requestId, event.errorText)

        // If the response event for this request is still waiting on a
        // corresponding ExtraInfo event, then wait to emit this event too.
        val queuedEventGroup = networkEventManager.getQueuedEventGroup(requestId)
        if (queuedEventGroup != null) {
            queuedEventGroup.loadingFailedEvent = event
        } else {
            emitLoadingFailed(event)
        }
    }

    private fun emitLoadingFailed(event: LoadingFailed) {
        val requestId = event.requestId
        val request = networkEventManager.getCDPRequest(requestId) ?: return
        // For certain requestIds we never receive requestWillBeSent event.
        // @see https://crbug.com/750469
        request.failureText = event.errorText

        request.response?.resolveBody(null)
        forgetRequest(request, true)
        emit(NetworkEvents.RequestFailed, request)
    }

    /**
     * Get the RequestWillBeSentExtraInfo event for a given request ID.
     * This allows access to additional request information including cookies.
     */
    fun getRequestWillBeSentExtraInfo(requestId: String): RequestWillBeSentExtraInfo? {
        return networkEventManager.getRequestWillBeSentExtraInfoEvent(requestId)
    }
}
