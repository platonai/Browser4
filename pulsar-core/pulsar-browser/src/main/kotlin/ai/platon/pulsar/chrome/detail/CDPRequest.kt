package ai.platon.pulsar.chrome.detail

import ai.platon.pulsar.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.types.fetch.HeaderEntry
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.Initiator
import ai.platon.cdt.kt.protocol.types.network.Request
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.pulsar.browser.WebDriver
import ai.platon.pulsar.browser.impl.BrowserProtocol
import ai.platon.pulsar.common.http.HttpStatus
import java.lang.ref.WeakReference
import java.util.*

class CDPRequest(
    val browserProtocol: BrowserProtocol,
    /**
     * Request identifier.
     */
    var requestId: String,
    /**
     * Request data.
     */
    val request: Request,
    /**
     * Request identifier.
     */
    val interceptionId: String? = null,

    val allowInterception: Boolean = false,

    val redirectChain: Queue<WeakReference<CDPRequest>> = LinkedList()
) {
    /**
     * Loader identifier. Empty string if the request is fetched from worker.
     */
    var loaderId: String? = null

    /**
     * URL of the document this request is loaded for.
     */
    var documentURL: String? = null

    /**
     * Request initiator.
     */
    var initiator: Initiator? = null

    /**
     * Type of this resource.
     */
    var type: ResourceType? = null

    var response: CDPResponse? = null

    var fromMemoryCache: Boolean = false

    internal var failureText: String? = null

    var interceptionHandled = false

    val url get() = request.url

    fun finalizeInterceptions() {
    }

    suspend fun continueRequest(overrides: ContinueRequestOverrides) {
        interceptionHandled = true

        val postDataBinaryBase64 = overrides.postData?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }
        val requestId =
            interceptionId ?: throw ChromeRPCException("InterceptionId is required by Fetch.continueRequest")

        try {
            browserProtocol.continueRequest(
                requestId,
                overrides.url, overrides.method, postDataBinaryBase64, overrides.headers
            )
        } catch (e: Exception) {
            interceptionHandled = false
        }
    }

    suspend fun respond(response: ResponseForRequest) {
        interceptionHandled = true

        val responseBody = when (val body = response.body) {
            is ByteArray -> body
            is String -> body.toByteArray()
            else -> body.toString().toByteArray()
        }

        val responseHeaders =
            response.headers.entries.mapTo(mutableListOf()) { (name, value) -> headerEntry(name, value) }
        response.contentType?.let { responseHeaders.add(headerEntry("content-type", it)) }
        if (!response.headers.containsKey("content-length")) {
            responseHeaders.add(headerEntry("content-length", responseBody.size.toString()))
        }
        val binaryResponseHeaders = responseHeaders.joinToString()

        val requestId = interceptionId ?: throw ChromeRPCException("InterceptionId is required by Fetch.fulfillRequest")

        val responseCode = response.status ?: 200
        val httpStatus = HttpStatus.valueOf(responseCode)

        try {
            val responseBodyBase64 = Base64.getEncoder().encodeToString(responseBody)
            // Provides response to the request.
            browserProtocol.fulfillRequest(
                requestId,
                responseCode, responseHeaders, binaryResponseHeaders, responseBodyBase64, httpStatus.reasonPhrase
            )
        } catch (e: Exception) {
            interceptionHandled = false
        }
    }

    suspend fun abort(abortErrorReason: ErrorReason) {
        interceptionHandled = true

        interceptionId?.let { browserProtocol.failRequest(it, abortErrorReason) }
            ?: throw ChromeRPCException("HTTPRequest is missing _interceptionId needed for Fetch.failRequest")
    }

    private fun headerEntry(name: String, value: String): HeaderEntry {
        return HeaderEntry(name, value)
    }
}
