package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.cdt.kt.protocol.events.network.LoadingFailed
import ai.platon.cdt.kt.protocol.events.network.LoadingFinished
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry

/**
 * The network events, used to intercept requests.
 * */
enum class NetworkEvents {
    Request,
    // may be removed, use Request instead
    RequestWillBeSent,
    RequestServedFromCache,
    Response,
    // may be removed, use Response instead
    ResponseReceived,
    RequestFailed,
    RequestFinished
}

enum class InterceptResolutionAction(val action: String) {
    Abort("abort"),
    Respond("respond"),
    Continue("continue"),
    Disabled("disabled"),
    None("none"),
    Handled("handled"),
}

/**
 * The request id for network API.
 * */
typealias NetworkRequestId = String

/**
 * The request id for fetch API.
 * */
typealias FetchRequestId = String

/**
 *
 */
class ContinueRequestOverrides {
    /**
     * If set, the request URL will change. This is not a redirect.
     */
    val url: String? = null
    val method: String? = null
    val postData: String? = null
    val headers: MutableList<HeaderEntry> = mutableListOf()
}

/**
 *
 */
class InterceptResolutionState {
    val action: InterceptResolutionAction? = null
    val priority: Int = 0
}

/**
 * Required response data to fulfill a request with.
 *
 *
 */
class ResponseForRequest {
    val status: Int? = null

    /**
     * Optional response headers. All values are converted to strings.
     */
    val headers: Map<String, String> = mutableMapOf()
    val contentType: String? = null
    val body: Any? = null
}

class QueuedEventGroup(
    var responseReceivedEvent: ResponseReceived,
    var loadingFinishedEvent: LoadingFinished? = null,
    var loadingFailedEvent: LoadingFailed? = null,
)

class RedirectInfo(
    val event: RequestWillBeSent,
    val fetchRequestId: FetchRequestId? = null
)
